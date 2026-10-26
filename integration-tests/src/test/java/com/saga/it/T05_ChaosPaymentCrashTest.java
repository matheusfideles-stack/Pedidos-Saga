package com.saga.it;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import com.saga.it.SagaEnvironment.Created;
import java.io.File;
import java.io.IOException;
import java.net.ServerSocket;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * TESTE DE CAOS: o payment-service roda como processo SEPARADO e e morto com kill -9 (Process.destroyForcibly)
 * no meio de uma saga, com o pagamento PENDING e a cobranca JA registrada no gateway (resposta perdida).
 * Ao subir de novo, o estado converge sem duplicar cobranca nem reserva.
 */
class T05_ChaosPaymentCrashTest {

    private static SagaEnvironment env;
    private static Process payment;

    @BeforeAll
    static void isolatePayment() {
        env = SagaEnvironment.get();
        env.stopAll("payment"); // o payment-service passa a ser um processo externo, que podemos matar de verdade
    }

    @AfterAll
    static void restoreInProcessPayment() {
        if (payment != null && payment.isAlive()) {
            payment.destroyForcibly();
        }
        env.start("payment");
    }

    private static Process launchPayment(Map<String, String> envVars, String logName) throws IOException {
        int port;
        try (ServerSocket s = new ServerSocket(0)) {
            port = s.getLocalPort();
        }
        List<String> cmd = new ArrayList<>();
        cmd.add(SagaEnvironment.javaExecutable().getPath());
        cmd.add("-Xmx512m");
        cmd.add("-cp");
        cmd.add(System.getProperty("surefire.test.class.path", System.getProperty("java.class.path")));
        cmd.add("com.saga.payment.PaymentServiceApplication");
        env.commonArgs("payment").stream().filter(a -> !a.startsWith("--server.port=")).forEach(cmd::add);
        cmd.add("--server.port=" + port);
        ProcessBuilder pb = new ProcessBuilder(cmd);
        pb.environment().putAll(envVars);
        File log = new File("target", logName);
        pb.redirectErrorStream(true).redirectOutput(log);
        Process p = pb.start();
        awaitReady(port, p);
        return p;
    }

    private static void awaitReady(int port, Process p) {
        HttpClient http = HttpClient.newHttpClient();
        await().atMost(Duration.ofSeconds(120)).pollInterval(Duration.ofSeconds(1)).until(() -> {
            if (!p.isAlive()) {
                throw new IllegalStateException("payment-service terminou (exit=" + p.exitValue() + ")");
            }
            try {
                return http.send(HttpRequest.newBuilder(URI.create("http://localhost:" + port
                                + "/actuator/health/readiness")).build(), HttpResponse.BodyHandlers.ofString())
                        .statusCode() == 200;
            } catch (IOException e) {
                return false;
            }
        });
    }

    @Test
    @DisplayName("kill -9 do payment-service com pagamento PENDING: ao reiniciar converge sem cobrar/reservar duas vezes")
    void paymentCrashMidSagaConverges() throws Exception {
        // 1) payment-service v1: o gateway registra a cobranca mas DEMORA 30s a responder (resultado ambiguo)
        payment = launchPayment(Map.of("GATEWAY_POST_COMMIT_DELAY_MS", "30000"), "chaos-payment-1.log");

        Created order = env.createOrder("chaos-1", "P-005", 2);

        // 2) espera o pagamento PENDING e a cobranca registrada no gateway; entao MATA o processo (kill -9)
        await().atMost(Duration.ofSeconds(90)).pollInterval(Duration.ofMillis(200)).until(() ->
                !env.paymentDb().queryForList("SELECT id FROM payments WHERE order_id = ? AND status = 'PENDING'",
                        order.id()).isEmpty());
        String paymentId = env.paymentDb().queryForObject("SELECT id::text FROM payments WHERE order_id = ?",
                String.class, order.id());
        await().atMost(Duration.ofSeconds(30)).until(() -> env.paymentDb().queryForObject(
                "SELECT count(*) FROM gateway_charges WHERE idempotency_key = ?", Long.class, paymentId) == 1L);

        payment.destroyForcibly();
        payment.waitFor();
        System.out.println("[T05] payment-service morto (kill -9) com pagamento " + paymentId + " PENDING");

        // estado no momento da falha: saga parada no meio
        assertThat(env.orderStatus(order.id())).isIn("PENDING", "INVENTORY_RESERVED");
        assertThat(env.paymentDb().queryForObject("SELECT status FROM payments WHERE id = ?::uuid", String.class,
                paymentId)).isEqualTo("PENDING");

        // 3) payment-service v2 (saudavel): o evento nao confirmado e reentregue; a chave de idempotencia
        //    faz o gateway devolver a cobranca ORIGINAL em vez de cobrar de novo
        payment = launchPayment(Map.of(), "chaos-payment-2.log");
        env.awaitStatus(order.id(), "CONFIRMED", Duration.ofSeconds(180));

        // 4) convergencia sem duplicidade
        assertThat(env.paymentDb().queryForObject("SELECT count(*) FROM payments WHERE order_id = ?", Long.class,
                order.id())).isEqualTo(1L);
        assertThat(env.paymentDb().queryForObject("SELECT status FROM payments WHERE order_id = ?", String.class,
                order.id())).isEqualTo("AUTHORIZED");
        assertThat(env.paymentDb().queryForObject("SELECT count(*) FROM gateway_charges WHERE idempotency_key = ?",
                Long.class, paymentId)).as("cobrancas no gateway para este pedido").isEqualTo(1L);
        assertThat(env.paymentDb().queryForObject(
                "SELECT count(*) FROM outbox WHERE aggregateid = ? AND type = 'PaymentAuthorized'", Long.class,
                order.id().toString())).isEqualTo(1L);
        await().atMost(Duration.ofSeconds(60)).untilAsserted(() ->
                assertThat(env.inventoryDb().queryForList("SELECT quantity || ':' || status FROM reservations "
                        + "WHERE order_id = ?", String.class, order.id())).containsExactly("2:COMMITTED"));
        assertThat(Files.readString(new File("target/chaos-payment-2.log").toPath())).contains("Started");
        System.out.println("[T05] convergiu: 1 pagamento, 1 cobranca no gateway, 1 reserva; pedido CONFIRMED");
    }
}
