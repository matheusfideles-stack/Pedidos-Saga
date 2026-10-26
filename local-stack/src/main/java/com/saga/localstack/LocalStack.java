package com.saga.localstack;

import com.saga.inventory.InventoryServiceApplication;
import com.saga.order.OrderServiceApplication;
import com.saga.payment.PaymentServiceApplication;
import com.saga.testsupport.TestInfra;
import java.io.File;
import java.io.FileWriter;
import java.io.PrintWriter;
import java.util.ArrayList;
import java.util.List;
import java.util.Properties;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.context.ConfigurableApplicationContext;

/**
 * Sobe o stack inteiro em um unico processo, sem Docker: PostgreSQL + Kafka KRaft + Redis embarcados,
 * Debezium (CDC) real e N replicas de cada servico em portas fixas.
 *
 * <pre>
 *   java -cp ... com.saga.localstack.LocalStack --replicas=3 --out=target/stack.properties
 * </pre>
 * Escreve {@code --out} com os enderecos e termina quando o arquivo {@code <out>.stop} aparecer (ou Ctrl-C).
 * Parametros extras: --gateway-latency-ms=N (latencia do gateway simulado), --failure-rate=0.0.
 */
public final class LocalStack {

    public static void main(String[] args) throws Exception {
        int replicas = intArg(args, "replicas", 1);
        String out = strArg(args, "out", "target/stack.properties");
        String gatewayLatency = strArg(args, "gateway-latency-ms", "30");
        String failureRate = strArg(args, "failure-rate", "0.0");
        boolean pinnedPorts = !"false".equals(strArg(args, "fixed-ports", "true"));

        TestInfra infra = TestInfra.get();
        System.out.println("[local-stack] infra modo=" + infra.mode());
        for (String db : List.of("orders", "inventory", "payments", "analytics", "analytics_java")) {
            infra.createDatabase(db);
        }

        List<ConfigurableApplicationContext> contexts = new ArrayList<>();
        Properties stack = new Properties();
        stack.setProperty("kafka.bootstrap", infra.kafkaBootstrap());
        stack.setProperty("pg.host", infra.pgHost());
        stack.setProperty("pg.port", String.valueOf(infra.pgPort()));
        stack.setProperty("pg.user", infra.pgUser());
        stack.setProperty("pg.password", infra.pgPassword());
        stack.setProperty("redis.port", String.valueOf(infra.redisPort()));
        for (String db : List.of("analytics", "analytics_java")) {
            stack.setProperty("jdbc." + db, infra.jdbcUrl(db));
            stack.setProperty("pgurl." + db, "postgres://" + infra.pgUser() + ":" + infra.pgPassword() + "@"
                    + infra.pgHost() + ":" + infra.pgPort() + "/" + db + "?sslmode=disable");
        }

        // a primeira replica de cada servico cria o schema (Flyway) antes das outras e do CDC
        for (int i = 0; i < replicas; i++) {
            contexts.add(start("order", i, pinnedPorts, infra, stack, gatewayLatency, failureRate));
            contexts.add(start("inventory", i, pinnedPorts, infra, stack, gatewayLatency, failureRate));
            contexts.add(start("payment", i, pinnedPorts, infra, stack, gatewayLatency, failureRate));
        }
        infra.startCdc("orders");
        infra.startCdc("inventory");
        infra.startCdc("payments");

        File outFile = new File(out);
        outFile.getParentFile().mkdirs();
        try (PrintWriter w = new PrintWriter(new FileWriter(outFile))) {
            stack.store(w, "local-stack");
        }
        File stop = new File(out + ".stop");
        stop.delete();
        Runtime.getRuntime().addShutdownHook(new Thread(() -> contexts.forEach(ConfigurableApplicationContext::close)));
        System.out.println("[local-stack] READY replicas=" + replicas + " -> " + outFile.getAbsolutePath());
        while (!stop.exists()) {
            Thread.sleep(500);
        }
        System.out.println("[local-stack] encerrando");
        contexts.forEach(ConfigurableApplicationContext::close);
        infra.close();
        System.exit(0);
    }

    private static ConfigurableApplicationContext start(String service, int index, boolean pinned, TestInfra infra,
                                                        Properties stack, String gatewayLatency, String failureRate) {
        int base = switch (service) {
            case "order" -> 8081;
            case "inventory" -> 8082;
            default -> 8083;
        };
        int port = pinned ? base + index * 10 : 0;
        String db = switch (service) {
            case "order" -> "orders";
            case "inventory" -> "inventory";
            default -> "payments";
        };
        List<String> args = new ArrayList<>(List.of(
                "--server.port=" + port,
                "--spring.datasource.url=" + infra.jdbcUrl(db),
                "--spring.datasource.username=" + infra.pgUser(),
                "--spring.datasource.password=" + infra.pgPassword(),
                "--spring.datasource.hikari.maximum-pool-size=30",
                "--spring.kafka.bootstrap-servers=" + infra.kafkaBootstrap(),
                "--spring.kafka.listener.concurrency=2",
                "--management.tracing.sampling.probability=0.1"));
        SpringApplicationBuilder builder;
        switch (service) {
            case "order" -> {
                args.add("--spring.data.redis.port=" + infra.redisPort());
                builder = OrderServiceApplication.application();
            }
            case "inventory" -> builder = InventoryServiceApplication.application();
            default -> {
                args.add("--payment.gateway.sim.latency-ms=" + gatewayLatency);
                args.add("--payment.gateway.sim.failure-rate=" + failureRate);
                builder = PaymentServiceApplication.application();
            }
        }
        ConfigurableApplicationContext ctx = builder.run(args.toArray(String[]::new));
        String actual = ctx.getEnvironment().getProperty("local.server.port");
        stack.setProperty(service + "." + index + ".url", "http://localhost:" + actual);
        System.out.println("[local-stack] " + service + "-" + index + " em http://localhost:" + actual);
        return ctx;
    }

    private static String strArg(String[] args, String name, String def) {
        for (String a : args) {
            if (a.startsWith("--" + name + "=")) {
                return a.substring(name.length() + 3);
            }
        }
        return def;
    }

    private static int intArg(String[] args, String name, int def) {
        return Integer.parseInt(strArg(args, name, String.valueOf(def)));
    }
}
