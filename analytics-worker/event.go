package main

import (
	"encoding/json"
	"fmt"
	"math"
	"time"
)

// Envelope é o contrato versionado dos eventos da saga (ver docs/event-contracts.md).
// Consumidores devem ignorar campos desconhecidos: encoding/json já faz isso.
type Envelope struct {
	EventID     string          `json:"eventId"`
	Type        string          `json:"type"`
	Version     int             `json:"version"`
	AggregateID string          `json:"aggregateId"`
	OccurredAt  time.Time       `json:"occurredAt"`
	Data        json.RawMessage `json:"data"`
}

type orderCreatedData struct {
	Total float64 `json:"total"`
}

// Métricas agregadas por janela.
const (
	MetricCreated   = "orders_created"
	MetricConfirmed = "orders_confirmed"
	MetricCancelled = "orders_cancelled"
)

// Window é a janela tumbling de 1 minuto (pelo tempo do EVENTO, não pelo de processamento).
const Window = time.Minute

// Parsed é um evento já validado, pronto para agregar.
type Parsed struct {
	EventID     string
	Metric      string
	WindowStart time.Time
	AmountCents int64
}

// Parse devolve (evento, true) se o evento interessa à analytics; (zero, false) se deve ser ignorado
// (tipos que não agregamos). Retorna erro para mensagens inválidas (veneno).
func Parse(value []byte) (Parsed, bool, error) {
	var env Envelope
	if err := json.Unmarshal(value, &env); err != nil {
		return Parsed{}, false, fmt.Errorf("envelope inválido: %w", err)
	}
	if env.EventID == "" || env.Type == "" || env.OccurredAt.IsZero() {
		return Parsed{}, false, fmt.Errorf("envelope incompleto: eventId=%q type=%q", env.EventID, env.Type)
	}
	p := Parsed{EventID: env.EventID, WindowStart: WindowStart(env.OccurredAt)}
	switch env.Type {
	case "OrderCreated":
		var d orderCreatedData
		if err := json.Unmarshal(env.Data, &d); err != nil {
			return Parsed{}, false, fmt.Errorf("dados de OrderCreated inválidos: %w", err)
		}
		p.Metric = MetricCreated
		p.AmountCents = int64(math.Round(d.Total * 100))
	case "OrderConfirmed":
		p.Metric = MetricConfirmed
	case "OrderCancelled":
		p.Metric = MetricCancelled
	default:
		return Parsed{}, false, nil
	}
	return p, true, nil
}

// WindowStart trunca para o início do minuto em UTC.
func WindowStart(t time.Time) time.Time {
	return t.UTC().Truncate(Window)
}
