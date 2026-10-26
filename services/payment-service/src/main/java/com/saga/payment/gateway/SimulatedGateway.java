package com.saga.payment.gateway;

import com.saga.payment.PaymentProperties;
import java.math.BigDecimal;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Gateway de pagamento SIMULADO (representa um terceiro). Latencia e falhas configuraveis em
 * {@code payment.gateway.sim.*}. O estado fica no banco para ser consistente entre replicas.
 * A cobranca e idempotente pela chave: repetir a chave devolve o resultado original.
 */
@RestController
@RequestMapping("/sim-gateway")
public class SimulatedGateway {

    public record ChargeRequest(String idempotencyKey, String customerId, BigDecimal amount) {
    }

    private final JdbcClient jdbc;
    private final PaymentProperties.Sim sim;

    public SimulatedGateway(JdbcClient jdbc, PaymentProperties props) {
        this.jdbc = jdbc;
        this.sim = props.getGateway().getSim();
    }

    @PostMapping("/charges")
    public ResponseEntity<PaymentGateway.GatewayResult> charge(@RequestBody ChargeRequest req) {
        sleep(sim.getLatencyMs() + (sim.getLatencyJitterMs() > 0
                ? ThreadLocalRandom.current().nextLong(sim.getLatencyJitterMs() + 1) : 0));
        if (sim.getFailureRate() > 0 && ThreadLocalRandom.current().nextDouble() < sim.getFailureRate()) {
            // falha ANTES de registrar a cobranca: o retry com a mesma chave e seguro
            return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE).build();
        }
        String status = (req.amount().compareTo(BigDecimal.valueOf(sim.getDeclineAbove())) > 0
                || req.customerId().startsWith("decline")) ? "DECLINED" : "AUTHORIZED";
        // INSERT ... ON CONFLICT DO NOTHING: a primeira chamada decide; repeticoes leem o resultado
        jdbc.sql("""
                        INSERT INTO gateway_charges (idempotency_key, customer_id, amount, status, ref)
                        VALUES (:k, :c, :a, :s, :r) ON CONFLICT (idempotency_key) DO NOTHING
                        """)
                .param("k", req.idempotencyKey()).param("c", req.customerId()).param("a", req.amount())
                .param("s", status).param("r", "gw_" + UUID.randomUUID().toString().substring(0, 12)).update();
        PaymentGateway.GatewayResult result = find(req.idempotencyKey()).orElseThrow();
        // resultado ambiguo: a cobranca JA foi registrada, mas a resposta demora (o cliente pode dar timeout)
        sleep(sim.getPostCommitDelayMs());
        return ResponseEntity.ok(result);
    }

    @GetMapping("/charges/{key}")
    public ResponseEntity<PaymentGateway.GatewayResult> get(@PathVariable String key) {
        return find(key).map(ResponseEntity::ok).orElse(ResponseEntity.notFound().build());
    }

    /** Estorno idempotente. Se a cobranca nao existe, deixa um VOIDED para impedir uma cobranca tardia. */
    @PostMapping("/charges/{key}/void")
    public PaymentGateway.GatewayResult voidCharge(@PathVariable String key) {
        jdbc.sql("""
                        INSERT INTO gateway_charges (idempotency_key, customer_id, amount, status, ref)
                        VALUES (:k, 'void', 0, 'VOIDED', :r)
                        ON CONFLICT (idempotency_key) DO UPDATE SET status = 'VOIDED'
                        """)
                .param("k", key).param("r", "gw_" + UUID.randomUUID().toString().substring(0, 12)).update();
        return find(key).orElseThrow();
    }

    private Optional<PaymentGateway.GatewayResult> find(String key) {
        return jdbc.sql("SELECT status, ref FROM gateway_charges WHERE idempotency_key = :k").param("k", key)
                .query((rs, n) -> new PaymentGateway.GatewayResult(rs.getString("status"), rs.getString("ref")))
                .optional();
    }

    /** Numero de cobrancas efetivas (AUTHORIZED) no gateway, para os testes provarem cobranca unica. */
    @GetMapping("/stats")
    public Map<String, Long> stats() {
        return Map.of("authorized", jdbc.sql("SELECT count(*) FROM gateway_charges WHERE status = 'AUTHORIZED'")
                .query(Long.class).single());
    }

    private static void sleep(long ms) {
        if (ms <= 0) {
            return;
        }
        try {
            Thread.sleep(ms);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
