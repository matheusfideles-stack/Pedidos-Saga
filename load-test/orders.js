// Teste de carga do fluxo de criacao de pedidos (k6).
//
//   k6 run -e BASE_URLS=http://localhost:8081 -e RATE=150 -e DURATION=60s -e LABEL=1-replica load-test/orders.js
//   k6 run -e BASE_URLS=http://localhost:8081,http://localhost:8091,http://localhost:8101 ... -e LABEL=3-replicas ...
//
// BASE_URLS  : replicas do order-service (round-robin no cliente, como um balanceador faria)
// RATE       : pedidos/s (modelo ABERTO: constant-arrival-rate, nao espera as respostas para disparar o proximo)
// POLL_EVERY : 1 em cada N pedidos e acompanhado ate o estado final (mede a saga ponta a ponta)
import http from 'k6/http';
import { check, sleep } from 'k6';
import { Counter, Trend } from 'k6/metrics';
// uuid v4 simples (evita dependencia de rede para importar bibliotecas)
function uuidv4() {
  return 'xxxxxxxx-xxxx-4xxx-yxxx-xxxxxxxxxxxx'.replace(/[xy]/g, (c) => {
    const r = (Math.random() * 16) | 0;
    return (c === 'x' ? r : (r & 0x3) | 0x8).toString(16);
  });
}

const BASES = (__ENV.BASE_URLS || 'http://localhost:8081').split(',');
const RATE = parseInt(__ENV.RATE || '100');
const DURATION = __ENV.DURATION || '60s';
const POLL_EVERY = parseInt(__ENV.POLL_EVERY || '25');
const LABEL = __ENV.LABEL || "run";
const DURATION_S = parseInt(DURATION) * (DURATION.endsWith("m") ? 60 : 1);
const PRODUCTS = ['P-001', 'P-002', 'P-004', 'P-005', 'P-006', 'P-007', 'P-008', 'P-010'];

const sagaE2E = new Trend('saga_e2e_seconds', true);
const confirmed = new Counter('saga_confirmed');
const cancelled = new Counter('saga_cancelled');
const stuck = new Counter('saga_not_terminal');
const accepted = new Counter('orders_accepted');

export const options = {
  scenarios: {
    orders: {
      executor: 'constant-arrival-rate',
      rate: RATE,
      timeUnit: '1s',
      duration: DURATION,
      preAllocatedVUs: Math.max(100, RATE * 2),
      maxVUs: Math.max(1000, RATE * 10),
    },
  },
  thresholds: {
    http_req_failed: ['rate<0.01'],
    'http_req_duration{name:create_order}': ['p(95)<800', 'p(99)<1500'],
  },
  summaryTrendStats: ['avg', 'min', 'med', 'p(90)', 'p(95)', 'p(99)', 'max'],
};

function pick(arr) {
  return arr[Math.floor(Math.random() * arr.length)];
}

export default function () {
  const base = BASES[(__VU + __ITER) % BASES.length];
  const items = [{ productId: pick(PRODUCTS), quantity: 1 + Math.floor(Math.random() * 3) }];
  if (Math.random() < 0.5) {
    items.push({ productId: pick(PRODUCTS), quantity: 1 });
  }
  // ~5% dos clientes sao recusados no pagamento (exercita a compensacao sob carga)
  const customerId = Math.random() < 0.05 ? `decline-${uuidv4()}` : `load-${uuidv4()}`;
  const res = http.post(`${base}/orders`, JSON.stringify({ customerId, items }), {
    headers: { 'Content-Type': 'application/json', 'Idempotency-Key': uuidv4() },
    tags: { name: 'create_order' },
  });
  const ok = check(res, { 'pedido criado (201)': (r) => r.status === 201 });
  if (ok) accepted.add(1);
  if (!ok || Math.random() >= 1 / POLL_EVERY) { // amostragem: ~1 em cada POLL_EVERY pedidos e acompanhado
    return;
  }

  // acompanha este pedido ate CONFIRMED/CANCELLED (saga completa: estoque -> pagamento)
  const id = res.json('id');
  const started = Date.now();
  for (let i = 0; i < 160; i++) {
    sleep(0.25);
    const s = http.get(`${base}/orders/${id}`, { tags: { name: 'get_order' } });
    const status = s.status === 200 ? s.json('status') : null;
    if (status === 'CONFIRMED' || status === 'CANCELLED') {
      sagaE2E.add((Date.now() - started) / 1000);
      (status === 'CONFIRMED' ? confirmed : cancelled).add(1);
      return;
    }
  }
  stuck.add(1);
}

export function handleSummary(data) {
  const d = data.metrics['http_req_duration{name:create_order}'].values;
  const reqs = data.metrics['http_reqs'].values;
  const e2e = data.metrics['saga_e2e_seconds'] ? data.metrics['saga_e2e_seconds'].values : null;
  const summary = {
    label: LABEL,
    target_rate_per_s: RATE,
    duration: DURATION,
    replicas: BASES.length,
    create_order: {
      throughput_per_s: data.metrics["orders_accepted"].values.count / DURATION_S,
      avg_ms: d.avg, p50_ms: d.med, p90_ms: d['p(90)'], p95_ms: d['p(95)'], p99_ms: d['p(99)'], max_ms: d.max,
      error_rate: data.metrics['http_req_failed'].values.rate,
    },
    saga_e2e_s: e2e ? { p50: e2e.med, p95: e2e['p(95)'], p99: e2e['p(99)'], max: e2e.max } : null,
    saga_confirmed: data.metrics['saga_confirmed'] ? data.metrics['saga_confirmed'].values.count : 0,
    saga_cancelled: data.metrics['saga_cancelled'] ? data.metrics['saga_cancelled'].values.count : 0,
    saga_not_terminal: data.metrics['saga_not_terminal'] ? data.metrics['saga_not_terminal'].values.count : 0,
    iterations: data.metrics['iterations'].values.count,
  };
  return {
    [`load-test/results/${LABEL}.json`]: JSON.stringify(summary, null, 2),
    stdout: `\n=== ${LABEL} ===\n${JSON.stringify(summary, null, 2)}\n`,
  };
}
