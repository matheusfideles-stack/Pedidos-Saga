package main

import (
	"context"
	"fmt"
	"os"
	"sync"
	"testing"
	"time"

	"github.com/jackc/pgx/v5/pgxpool"
)

func envelope(id, typ, at, data string) []byte {
	return []byte(fmt.Sprintf(`{"eventId":%q,"type":%q,"version":1,"aggregateId":"o-1","occurredAt":%q,"data":%s,"campoNovo":"ignorado"}`,
		id, typ, at, data))
}

func TestParseOrderCreatedWindowAndAmount(t *testing.T) {
	p, ok, err := Parse(envelope("11111111-1111-1111-1111-111111111111", "OrderCreated",
		"2026-10-25T15:04:59.999Z", `{"orderId":"x","total":820.5}`))
	if err != nil || !ok {
		t.Fatalf("esperava evento relevante, got ok=%v err=%v", ok, err)
	}
	if p.Metric != MetricCreated || p.AmountCents != 82050 {
		t.Fatalf("metric/amount errados: %+v", p)
	}
	want := time.Date(2026, 10, 25, 15, 4, 0, 0, time.UTC)
	if !p.WindowStart.Equal(want) {
		t.Fatalf("janela = %v, esperado %v", p.WindowStart, want)
	}
}

func TestWindowBoundaries(t *testing.T) {
	a := WindowStart(time.Date(2026, 1, 1, 10, 0, 59, 0, time.UTC))
	b := WindowStart(time.Date(2026, 1, 1, 10, 1, 0, 0, time.UTC))
	if a.Equal(b) || b.Sub(a) != time.Minute {
		t.Fatalf("janelas de 1 minuto: %v / %v", a, b)
	}
	// fuso horario nao altera a janela (sempre UTC)
	loc := time.FixedZone("BRT", -3*3600)
	if !WindowStart(time.Date(2026, 1, 1, 7, 0, 30, 0, loc)).Equal(WindowStart(time.Date(2026, 1, 1, 10, 0, 59, 0, time.UTC))) {
		t.Fatal("janela deve ser calculada em UTC")
	}
}

func TestParseIgnoresOtherTypesAndRejectsGarbage(t *testing.T) {
	if _, ok, err := Parse(envelope("22222222-2222-2222-2222-222222222222", "InventoryReserved", "2026-01-01T00:00:00Z", `{}`)); ok || err != nil {
		t.Fatalf("tipo irrelevante deve ser ignorado sem erro (ok=%v err=%v)", ok, err)
	}
	if _, _, err := Parse([]byte("isto-nao-e-json")); err == nil {
		t.Fatal("JSON inválido deve dar erro")
	}
	if _, _, err := Parse([]byte(`{"type":"OrderCreated"}`)); err == nil {
		t.Fatal("envelope incompleto deve dar erro")
	}
}

func TestAggregateGroupsByWindowAndMetric(t *testing.T) {
	w1 := time.Date(2026, 1, 1, 10, 0, 0, 0, time.UTC)
	w2 := w1.Add(time.Minute)
	agg := Aggregate([]Parsed{
		{EventID: "a", Metric: MetricCreated, WindowStart: w1, AmountCents: 100},
		{EventID: "b", Metric: MetricCreated, WindowStart: w1, AmountCents: 250},
		{EventID: "c", Metric: MetricCreated, WindowStart: w2, AmountCents: 10},
		{EventID: "d", Metric: MetricConfirmed, WindowStart: w1},
	})
	if got := agg[windowKey{w1, MetricCreated}]; got.count != 2 || got.amount != 350 {
		t.Fatalf("w1/created = %+v", got)
	}
	if got := agg[windowKey{w2, MetricCreated}]; got.count != 1 || got.amount != 10 {
		t.Fatalf("w2/created = %+v", got)
	}
	if got := agg[windowKey{w1, MetricConfirmed}]; got.count != 1 {
		t.Fatalf("w1/confirmed = %+v", got)
	}
}

// ---- testes com PostgreSQL real (TEST_DATABASE_URL); pulados se não definido ----

func testProcessor(t *testing.T) (*Processor, *pgxpool.Pool) {
	url := os.Getenv("TEST_DATABASE_URL")
	if url == "" {
		t.Skip("TEST_DATABASE_URL não definido")
	}
	ctx := context.Background()
	pool, err := pgxpool.New(ctx, url)
	if err != nil {
		t.Fatal(err)
	}
	t.Cleanup(pool.Close)
	p := NewProcessor(pool)
	if err := p.Migrate(ctx); err != nil {
		t.Fatal(err)
	}
	if _, err := pool.Exec(ctx, "TRUNCATE analytics_processed_events, order_window_stats"); err != nil {
		t.Fatal(err)
	}
	return p, pool
}

func uuid(n int) string { return fmt.Sprintf("00000000-0000-0000-0000-%012d", n) }

func counts(t *testing.T, pool *pgxpool.Pool, metric string) (count, amount int64) {
	err := pool.QueryRow(context.Background(),
		"SELECT COALESCE(SUM(event_count),0), COALESCE(SUM(amount_cents),0) FROM order_window_stats WHERE metric=$1", metric).
		Scan(&count, &amount)
	if err != nil {
		t.Fatal(err)
	}
	return
}

func TestDBSameBatchReappliedManyTimesCountsOnce(t *testing.T) {
	p, pool := testProcessor(t)
	w := time.Date(2026, 1, 1, 10, 0, 0, 0, time.UTC)
	batch := []Parsed{
		{EventID: uuid(1), Metric: MetricCreated, WindowStart: w, AmountCents: 1000},
		{EventID: uuid(2), Metric: MetricCreated, WindowStart: w, AmountCents: 500},
	}
	for _, times := range []int{1, 2, 10, 100} {
		for i := 0; i < times; i++ {
			if _, err := p.Apply(context.Background(), batch); err != nil {
				t.Fatal(err)
			}
		}
	}
	if c, a := counts(t, pool, MetricCreated); c != 2 || a != 1500 {
		t.Fatalf("efeito deve ocorrer uma única vez: count=%d amount=%d", c, a)
	}
}

func TestDBDuplicatesInsideBatchAndAcrossBatches(t *testing.T) {
	p, pool := testProcessor(t)
	w := time.Date(2026, 1, 1, 10, 0, 0, 0, time.UTC)
	e := Parsed{EventID: uuid(7), Metric: MetricConfirmed, WindowStart: w}
	res, err := p.Apply(context.Background(), []Parsed{e, e, e})
	if err != nil || res.Applied != 1 || res.Duplicates != 2 {
		t.Fatalf("lote com duplicatas: %+v err=%v", res, err)
	}
	res, err = p.Apply(context.Background(), []Parsed{e})
	if err != nil || res.Applied != 0 || res.Duplicates != 1 {
		t.Fatalf("reentrega: %+v err=%v", res, err)
	}
	if c, _ := counts(t, pool, MetricConfirmed); c != 1 {
		t.Fatalf("count = %d", c)
	}
}

func TestDBConcurrentWorkersDoNotDoubleCountNorDeadlock(t *testing.T) {
	p, pool := testProcessor(t)
	w := time.Date(2026, 1, 1, 10, 0, 0, 0, time.UTC)
	var events []Parsed
	for i := 0; i < 200; i++ {
		events = append(events, Parsed{EventID: uuid(1000 + i), Metric: MetricCreated, WindowStart: w.Add(time.Duration(i%3) * time.Minute), AmountCents: 100})
	}
	var wg sync.WaitGroup
	errs := make(chan error, 8)
	for worker := 0; worker < 8; worker++ { // 8 réplicas processando o MESMO lote ao mesmo tempo
		wg.Add(1)
		go func() {
			defer wg.Done()
			if _, err := p.Apply(context.Background(), events); err != nil {
				errs <- err
			}
		}()
	}
	wg.Wait()
	close(errs)
	for err := range errs {
		t.Fatal(err)
	}
	if c, a := counts(t, pool, MetricCreated); c != 200 || a != 20000 {
		t.Fatalf("count=%d amount=%d (esperado 200 / 20000)", c, a)
	}
}
