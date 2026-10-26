// analytics-worker: consome os eventos de pedido do Kafka, agrega em janelas de 1 minuto e grava em PostgreSQL.
//
//   - idempotente: eventId registrado na mesma transação dos incrementos (ver processor.go)
//   - offset só é confirmado DEPOIS do commit no banco (at-least-once + idempotência = efeito único)
//   - métricas Prometheus em :9100/metrics
package main

import (
	"context"
	"errors"
	"fmt"
	"log/slog"
	"net/http"
	"os"
	"os/signal"
	"strconv"
	"strings"
	"syscall"
	"time"

	"github.com/jackc/pgx/v5/pgxpool"
	"github.com/prometheus/client_golang/prometheus"
	"github.com/prometheus/client_golang/prometheus/promhttp"
	"github.com/segmentio/kafka-go"
)

type config struct {
	brokers      []string
	topic        string
	group        string
	databaseURL  string
	batchSize    int
	batchTimeout time.Duration
	metricsAddr  string
	startOffset  int64
}

func env(key, def string) string {
	if v := os.Getenv(key); v != "" {
		return v
	}
	return def
}

func loadConfig() config {
	batch, _ := strconv.Atoi(env("BATCH_SIZE", "500"))
	timeout, err := time.ParseDuration(env("BATCH_TIMEOUT", "300ms"))
	if err != nil {
		timeout = 300 * time.Millisecond
	}
	start := kafka.FirstOffset
	if env("START_OFFSET", "earliest") == "latest" {
		start = kafka.LastOffset
	}
	return config{
		brokers:      strings.Split(env("KAFKA_BROKERS", "localhost:9094"), ","),
		topic:        env("KAFKA_TOPIC", "order.events"),
		group:        env("KAFKA_GROUP", "analytics-worker"),
		databaseURL:  env("DATABASE_URL", "postgres://saga:saga@localhost:5444/analytics"),
		batchSize:    batch,
		batchTimeout: timeout,
		metricsAddr:  env("METRICS_ADDR", ":9100"),
		startOffset:  start,
	}
}

func main() {
	log := slog.New(slog.NewJSONHandler(os.Stdout, nil))
	slog.SetDefault(log)
	cfg := loadConfig()

	ctx, stop := signal.NotifyContext(context.Background(), syscall.SIGINT, syscall.SIGTERM)
	defer stop()

	pool, err := pgxpool.New(ctx, cfg.databaseURL)
	if err != nil {
		log.Error("conexão com o banco", "err", err)
		os.Exit(1)
	}
	defer pool.Close()
	proc := NewProcessor(pool)
	if err := retry(ctx, "migrate", func() error { return proc.Migrate(ctx) }); err != nil {
		log.Error("migração", "err", err)
		os.Exit(1)
	}

	registerMetrics(prometheus.DefaultRegisterer)
	go serveMetrics(cfg.metricsAddr, pool)

	if err := waitForTopic(ctx, cfg.brokers, cfg.topic); err != nil {
		log.Error("aguardando o tópico", "err", err)
		os.Exit(1)
	}
	reader := kafka.NewReader(kafka.ReaderConfig{
		Brokers:        cfg.brokers,
		GroupID:        cfg.group,
		Topic:          cfg.topic,
		StartOffset:    cfg.startOffset,
		MinBytes:       1,
		MaxBytes:       10 << 20,
		MaxWait:        200 * time.Millisecond,
		CommitInterval: 0, // commit SÍNCRONO e manual: nunca antes da gravação
		// o worker pode subir ANTES do tópico existir (ordem de partida no compose/k8s): reavalia as partições
		WatchPartitionChanges:  true,
		PartitionWatchInterval: 5 * time.Second,
		Dialer:                 &kafka.Dialer{Timeout: 5 * time.Second},
	})
	defer reader.Close()
	go reportLag(ctx, reader)

	log.Info("analytics-worker iniciado", "topic", cfg.topic, "group", cfg.group, "batch", cfg.batchSize)
	if err := run(ctx, reader, proc, cfg); err != nil && !errors.Is(err, context.Canceled) {
		log.Error("worker encerrado com erro", "err", err)
		os.Exit(1)
	}
	log.Info("analytics-worker encerrado")
}

// run é o laço principal: busca um lote, grava (1 transação) e SÓ ENTÃO confirma os offsets.
func run(ctx context.Context, reader *kafka.Reader, proc *Processor, cfg config) error {
	for ctx.Err() == nil {
		msgs := fetchBatch(ctx, reader, cfg.batchSize, cfg.batchTimeout)
		if len(msgs) == 0 {
			continue
		}
		batchSize.Observe(float64(len(msgs)))

		parsed := make([]Parsed, 0, len(msgs))
		for _, m := range msgs {
			p, relevant, err := Parse(m.Value)
			switch {
			case err != nil:
				// mensagem venenosa: registra e segue (o offset avança; ela não pode travar a partição)
				eventsTotal.WithLabelValues("invalid").Inc()
				slog.Warn("mensagem inválida descartada", "partition", m.Partition, "offset", m.Offset, "err", err)
			case !relevant:
				eventsTotal.WithLabelValues("ignored").Inc()
			default:
				parsed = append(parsed, p)
			}
		}

		// grava com retry/backoff: enquanto o banco não aceitar, NÃO confirma o offset
		var res Result
		start := time.Now()
		err := retry(ctx, "apply", func() error {
			var e error
			res, e = proc.Apply(ctx, parsed)
			if e != nil {
				errorsTotal.WithLabelValues("db").Inc()
			}
			return e
		})
		if err != nil {
			return err
		}
		batchDuration.Observe(time.Since(start).Seconds())
		eventsTotal.WithLabelValues("applied").Add(float64(res.Applied))
		eventsTotal.WithLabelValues("duplicate").Add(float64(res.Duplicates))

		// ponto de commit do offset: depois do COMMIT do banco
		if err := reader.CommitMessages(ctx, msgs...); err != nil {
			errorsTotal.WithLabelValues("commit").Inc()
			// rebalance no meio: as mensagens serão reentregues e descartadas como duplicatas
			slog.Warn("falha ao confirmar offsets (serão reentregues; idempotência protege)", "err", err)
			continue
		}
		offsetCommits.Inc()
	}
	return ctx.Err()
}

// fetchBatch acumula até size mensagens ou até o timeout desde a primeira.
func fetchBatch(ctx context.Context, reader *kafka.Reader, size int, timeout time.Duration) []kafka.Message {
	var msgs []kafka.Message
	deadline := time.Now().Add(timeout)
	for len(msgs) < size {
		var fctx context.Context
		var cancel context.CancelFunc
		if len(msgs) == 0 {
			fctx, cancel = context.WithTimeout(ctx, 2*time.Second) // espera a primeira mensagem
		} else {
			remaining := time.Until(deadline)
			if remaining <= 0 {
				break
			}
			fctx, cancel = context.WithTimeout(ctx, remaining)
		}
		m, err := reader.FetchMessage(fctx)
		cancel()
		if err != nil {
			if ctx.Err() != nil {
				return msgs
			}
			if !errors.Is(err, context.DeadlineExceeded) {
				errorsTotal.WithLabelValues("fetch").Inc()
				slog.Warn("fetch", "err", err)
				time.Sleep(200 * time.Millisecond)
			}
			break
		}
		msgs = append(msgs, m)
	}
	return msgs
}

// retry com backoff exponencial até o contexto ser cancelado.
func retry(ctx context.Context, name string, fn func() error) error {
	delay := 100 * time.Millisecond
	for {
		err := fn()
		if err == nil {
			return nil
		}
		if ctx.Err() != nil {
			return ctx.Err()
		}
		slog.Warn("tentando novamente", "op", name, "err", err, "em", delay)
		select {
		case <-ctx.Done():
			return ctx.Err()
		case <-time.After(delay):
		}
		if delay < 5*time.Second {
			delay *= 2
		}
	}
}

func reportLag(ctx context.Context, reader *kafka.Reader) {
	t := time.NewTicker(2 * time.Second)
	defer t.Stop()
	for {
		select {
		case <-ctx.Done():
			return
		case <-t.C:
			consumerLag.Set(float64(reader.Stats().Lag))
		}
	}
}

func serveMetrics(addr string, pool *pgxpool.Pool) {
	mux := http.NewServeMux()
	mux.Handle("/metrics", promhttp.Handler())
	mux.HandleFunc("/healthz", func(w http.ResponseWriter, _ *http.Request) { w.WriteHeader(http.StatusOK) }) // liveness
	mux.HandleFunc("/readyz", func(w http.ResponseWriter, r *http.Request) {                                  // readiness
		ctx, cancel := context.WithTimeout(r.Context(), time.Second)
		defer cancel()
		if err := pool.Ping(ctx); err != nil {
			http.Error(w, err.Error(), http.StatusServiceUnavailable)
			return
		}
		w.WriteHeader(http.StatusOK)
	})
	srv := &http.Server{Addr: addr, Handler: mux, ReadHeaderTimeout: 5 * time.Second}
	if err := srv.ListenAndServe(); err != nil && !errors.Is(err, http.ErrServerClosed) {
		slog.Error("servidor de métricas", "err", err)
	}
}

// waitForTopic bloqueia até o tópico existir com partições. O worker pode subir ANTES de quem produz no tópico
// (docker compose / Kubernetes sobem tudo junto); entrar no grupo com o tópico inexistente deixaria o reader sem partições.
func waitForTopic(ctx context.Context, brokers []string, topic string) error {
	return retry(ctx, "aguardar-topico", func() error {
		conn, err := kafka.DialContext(ctx, "tcp", brokers[0])
		if err != nil {
			return err
		}
		defer conn.Close()
		parts, err := conn.ReadPartitions(topic)
		if err != nil {
			return err
		}
		if len(parts) == 0 {
			return fmt.Errorf("tópico %s ainda sem partições", topic)
		}
		return nil
	})
}
