package main

import (
	"context"
	"fmt"
	"sort"
	"time"

	"github.com/jackc/pgx/v5"
	"github.com/jackc/pgx/v5/pgxpool"
)

const schema = `
CREATE TABLE IF NOT EXISTS analytics_processed_events (
    event_id     UUID        PRIMARY KEY,
    processed_at TIMESTAMPTZ NOT NULL DEFAULT now()
);
CREATE TABLE IF NOT EXISTS order_window_stats (
    window_start TIMESTAMPTZ NOT NULL,
    metric       TEXT        NOT NULL,
    event_count  BIGINT      NOT NULL DEFAULT 0,
    amount_cents BIGINT      NOT NULL DEFAULT 0,
    PRIMARY KEY (window_start, metric)
);`

// Processor grava as agregações. Toda a escrita de um lote acontece em UMA transação:
// o registro dos eventIds processados e os incrementos das janelas. Assim a entrega at-least-once do
// Kafka (duplicatas, reentregas após rebalance) nunca conta um evento duas vezes.
type Processor struct {
	pool *pgxpool.Pool
}

func NewProcessor(pool *pgxpool.Pool) *Processor { return &Processor{pool: pool} }

func (p *Processor) Migrate(ctx context.Context) error {
	_, err := p.pool.Exec(ctx, schema)
	return err
}

// Result resume um lote.
type Result struct {
	Applied    int
	Duplicates int
}

type windowKey struct {
	start  time.Time
	metric string
}

type delta struct {
	count  int64
	amount int64
}

// Aggregate agrupa eventos por (janela, métrica). Função pura (testável sem banco).
func Aggregate(events []Parsed) map[windowKey]delta {
	out := make(map[windowKey]delta)
	for _, e := range events {
		k := windowKey{e.WindowStart, e.Metric}
		d := out[k]
		d.count++
		d.amount += e.AmountCents
		out[k] = d
	}
	return out
}

// Apply grava o lote de forma idempotente. Só retorna sem erro depois do COMMIT: é nesse ponto (e só
// nele) que o chamador pode confirmar os offsets do Kafka.
func (p *Processor) Apply(ctx context.Context, events []Parsed) (Result, error) {
	if len(events) == 0 {
		return Result{}, nil
	}
	// duplicatas dentro do próprio lote: só a primeira ocorrência conta
	seen := make(map[string]struct{}, len(events))
	unique := make([]Parsed, 0, len(events))
	ids := make([]string, 0, len(events))
	for _, e := range events {
		if _, dup := seen[e.EventID]; dup {
			continue
		}
		seen[e.EventID] = struct{}{}
		unique = append(unique, e)
		ids = append(ids, e.EventID)
	}

	tx, err := p.pool.Begin(ctx)
	if err != nil {
		return Result{}, fmt.Errorf("begin: %w", err)
	}
	defer func() { _ = tx.Rollback(ctx) }() // no-op depois do commit

	// 1) registra os eventIds; RETURNING devolve apenas os que eram NOVOS
	rows, err := tx.Query(ctx, `
		INSERT INTO analytics_processed_events (event_id)
		SELECT unnest($1::uuid[]) ON CONFLICT DO NOTHING RETURNING event_id::text`, ids)
	if err != nil {
		return Result{}, fmt.Errorf("registrar eventos: %w", err)
	}
	fresh := make(map[string]struct{}, len(ids))
	for rows.Next() {
		var id string
		if err := rows.Scan(&id); err != nil {
			rows.Close()
			return Result{}, err
		}
		fresh[id] = struct{}{}
	}
	rows.Close()
	if err := rows.Err(); err != nil {
		return Result{}, err
	}

	// 2) agrega só os novos
	newEvents := make([]Parsed, 0, len(fresh))
	for _, e := range unique {
		if _, ok := fresh[e.EventID]; ok {
			newEvents = append(newEvents, e)
		}
	}
	agg := Aggregate(newEvents)
	keys := make([]windowKey, 0, len(agg))
	for k := range agg {
		keys = append(keys, k)
	}
	// ordem estável de chaves: réplicas concorrentes atualizando as mesmas janelas não geram deadlock
	sort.Slice(keys, func(i, j int) bool {
		if !keys[i].start.Equal(keys[j].start) {
			return keys[i].start.Before(keys[j].start)
		}
		return keys[i].metric < keys[j].metric
	})

	batch := &pgx.Batch{}
	for _, k := range keys {
		d := agg[k]
		batch.Queue(`
			INSERT INTO order_window_stats (window_start, metric, event_count, amount_cents)
			VALUES ($1, $2, $3, $4)
			ON CONFLICT (window_start, metric) DO UPDATE SET
			  event_count  = order_window_stats.event_count  + EXCLUDED.event_count,
			  amount_cents = order_window_stats.amount_cents + EXCLUDED.amount_cents`,
			k.start, k.metric, d.count, d.amount)
	}
	br := tx.SendBatch(ctx, batch)
	for range keys {
		if _, err := br.Exec(); err != nil {
			_ = br.Close()
			return Result{}, fmt.Errorf("upsert janela: %w", err)
		}
	}
	if err := br.Close(); err != nil {
		return Result{}, err
	}

	if err := tx.Commit(ctx); err != nil {
		return Result{}, fmt.Errorf("commit: %w", err)
	}
	return Result{Applied: len(newEvents), Duplicates: len(events) - len(newEvents)}, nil
}
