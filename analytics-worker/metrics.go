package main

import "github.com/prometheus/client_golang/prometheus"

// Métricas Prometheus expostas em /metrics.
var (
	eventsTotal = prometheus.NewCounterVec(prometheus.CounterOpts{
		Name: "analytics_events_total",
		Help: "Eventos consumidos por resultado: applied, duplicate, ignored, invalid.",
	}, []string{"result"})

	batchDuration = prometheus.NewHistogram(prometheus.HistogramOpts{
		Name:    "analytics_batch_duration_seconds",
		Help:    "Duração da gravação de um lote (transação PostgreSQL).",
		Buckets: prometheus.ExponentialBuckets(0.001, 2, 14),
	})

	batchSize = prometheus.NewHistogram(prometheus.HistogramOpts{
		Name:    "analytics_batch_size",
		Help:    "Mensagens por lote.",
		Buckets: prometheus.ExponentialBuckets(1, 2, 12),
	})

	offsetCommits = prometheus.NewCounter(prometheus.CounterOpts{
		Name: "analytics_offset_commits_total",
		Help: "Confirmações de offset (sempre DEPOIS do commit no banco).",
	})

	errorsTotal = prometheus.NewCounterVec(prometheus.CounterOpts{
		Name: "analytics_errors_total",
		Help: "Erros por etapa: fetch, db, commit.",
	}, []string{"stage"})

	consumerLag = prometheus.NewGauge(prometheus.GaugeOpts{
		Name: "analytics_consumer_lag",
		Help: "Lag atual do consumer group (mensagens).",
	})
)

func registerMetrics(reg prometheus.Registerer) {
	reg.MustRegister(eventsTotal, batchDuration, batchSize, offsetCommits, errorsTotal, consumerLag)
}
