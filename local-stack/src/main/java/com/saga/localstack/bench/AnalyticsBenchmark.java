package com.saga.localstack.bench;

import java.io.File;
import java.io.FileInputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.Statement;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;
import org.apache.kafka.clients.admin.AdminClient;
import org.apache.kafka.clients.admin.NewTopic;
import org.apache.kafka.clients.producer.KafkaProducer;
import org.apache.kafka.clients.producer.ProducerConfig;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.apache.kafka.common.serialization.StringSerializer;

/**
 * Compara o throughput do analytics-worker (Go) com o consumidor Java equivalente.
 * Pre-carrega N eventos em um topico dedicado e mede quanto cada implementacao leva para drenar tudo
 * (1 e 3 instancias do mesmo consumer group). Requer o local-stack no ar e o binario do worker Go.
 *
 * <pre>java -cp ... com.saga.localstack.bench.AnalyticsBenchmark --events=300000 --go=analytics-worker/target/analytics-worker.exe</pre>
 */
public final class AnalyticsBenchmark {

    static final String TOPIC = "analytics.bench";

    public static void main(String[] args) throws Exception {
        int events = Integer.parseInt(arg(args, "events", "300000"));
        String goBinary = arg(args, "go", "analytics-worker/target/analytics-worker.exe");
        String stackFile = arg(args, "stack", "local-stack/target/stack.properties");
        String report = arg(args, "report", "docs/benchmarks/analytics-throughput.md");

        Properties stack = new Properties();
        try (FileInputStream in = new FileInputStream(stackFile)) {
            stack.load(in);
        }
        String bootstrap = stack.getProperty("kafka.bootstrap");
        String user = stack.getProperty("pg.user");
        String password = stack.getProperty("pg.password");
        String goUrl = stack.getProperty("pgurl.analytics");
        String goJdbc = stack.getProperty("jdbc.analytics");
        String javaJdbc = stack.getProperty("jdbc.analytics_java");

        JavaAnalyticsConsumer.migrate(goJdbc, user, password);
        JavaAnalyticsConsumer.migrate(javaJdbc, user, password);

        int relevant = preload(bootstrap, events);
        System.out.printf("[bench] %d eventos publicados em %s (%d relevantes para agregacao)%n", events, TOPIC, relevant);

        List<String[]> rows = new ArrayList<>();
        for (int instances : new int[]{1, 3}) {
            double java = runJava(bootstrap, javaJdbc, user, password, instances, relevant);
            double go = runGo(goBinary, bootstrap, goUrl, goJdbc, user, password, instances, relevant);
            rows.add(new String[]{String.valueOf(instances), String.format("%,.0f", java), String.format("%,.0f", go),
                    String.format("%.2fx", go / java)});
            System.out.printf("[bench] %d instancia(s): Java %.0f ev/s | Go %.0f ev/s%n", instances, java, go);
        }

        StringBuilder md = new StringBuilder();
        md.append("# Throughput: consumidor Go x Java (analytics)\n\n");
        md.append("Gerado por `AnalyticsBenchmark` em ").append(Instant.now()).append(".\n\n");
        md.append("- **Carga**: ").append(String.format("%,d", relevant)).append(" eventos de pedido pre-carregados em ")
                .append("`analytics.bench` (6 particoes), 120 janelas de 1 minuto.\n");
        md.append("- **Mesmo algoritmo nos dois**: lote de ate 500 mensagens -> 1 transacao (registra `eventId` + upsert ")
                .append("das janelas) -> commit do offset **depois** do commit no banco.\n");
        md.append("- **Ambiente**: tudo na mesma maquina (PostgreSQL e Kafka embarcados, ").append(System.getProperty("os.name"))
                .append(", ").append(Runtime.getRuntime().availableProcessors()).append(" CPUs), JDK ")
                .append(System.getProperty("java.version")).append(". Numeros sao RELATIVOS: ")
                .append("o gargalo e o PostgreSQL local compartilhado, nao a linguagem.\n\n");
        md.append("| Instancias do consumer group | Java (eventos/s) | Go (eventos/s) | Go / Java |\n");
        md.append("|---|---|---|---|\n");
        for (String[] r : rows) {
            md.append("| ").append(String.join(" | ", r)).append(" |\n");
        }
        md.append("\nMedicao: tempo entre o primeiro evento gravado e o ultimo (exclui o join no grupo).\n");
        Path out = Path.of(report);
        Files.createDirectories(out.getParent());
        Files.writeString(out, md.toString());
        System.out.println(md);
        System.exit(0);
    }

    /** Publica N eventos sinteticos; devolve quantos sao relevantes (OrderCreated/Confirmed/Cancelled). */
    private static int preload(String bootstrap, int events) throws Exception {
        Properties ap = new Properties();
        ap.put("bootstrap.servers", bootstrap);
        try (AdminClient admin = AdminClient.create(ap)) {
            admin.deleteTopics(List.of(TOPIC)).all().whenComplete((v, e) -> { });
            Thread.sleep(1500);
            admin.createTopics(List.of(new NewTopic(TOPIC, 6, (short) 1))).all().get();
        }
        Properties p = new Properties();
        p.put(ProducerConfig.BOOTSTRAP_SERVERS_CONFIG, bootstrap);
        p.put(ProducerConfig.KEY_SERIALIZER_CLASS_CONFIG, StringSerializer.class.getName());
        p.put(ProducerConfig.VALUE_SERIALIZER_CLASS_CONFIG, StringSerializer.class.getName());
        p.put(ProducerConfig.LINGER_MS_CONFIG, "20");
        p.put(ProducerConfig.BATCH_SIZE_CONFIG, "131072");
        p.put(ProducerConfig.COMPRESSION_TYPE_CONFIG, "lz4");
        int relevant = 0;
        Instant base = Instant.now().truncatedTo(ChronoUnit.HOURS);
        try (KafkaProducer<String, String> producer = new KafkaProducer<>(p)) {
            ThreadLocalRandom rnd = ThreadLocalRandom.current();
            for (int i = 0; i < events; i++) {
                String orderId = UUID.randomUUID().toString();
                int dice = rnd.nextInt(100);
                String type;
                String data;
                if (dice < 40) {
                    type = "OrderCreated";
                    data = "{\"orderId\":\"" + orderId + "\",\"total\":" + (10 + rnd.nextInt(5000)) + ".50}";
                } else if (dice < 75) {
                    type = "OrderConfirmed";
                    data = "{\"orderId\":\"" + orderId + "\"}";
                } else if (dice < 85) {
                    type = "OrderCancelled";
                    data = "{\"orderId\":\"" + orderId + "\",\"reason\":\"x\"}";
                } else {
                    type = "InventoryReserved"; // irrelevante: deve ser ignorado
                    data = "{\"orderId\":\"" + orderId + "\"}";
                }
                if (!type.equals("InventoryReserved")) {
                    relevant++;
                }
                String at = base.minus(rnd.nextInt(120), ChronoUnit.MINUTES).plusMillis(rnd.nextInt(60_000)).toString();
                String json = "{\"eventId\":\"" + UUID.randomUUID() + "\",\"type\":\"" + type + "\",\"version\":1,"
                        + "\"aggregateId\":\"" + orderId + "\",\"occurredAt\":\"" + at + "\",\"data\":" + data + "}";
                producer.send(new ProducerRecord<>(TOPIC, orderId, json));
            }
            producer.flush();
        }
        return relevant;
    }

    private static double runJava(String bootstrap, String jdbc, String user, String password, int instances,
                                  int relevant) throws Exception {
        truncate(jdbc, user, password);
        String group = "bench-java-" + UUID.randomUUID();
        List<JavaAnalyticsConsumer> consumers = new ArrayList<>();
        List<Thread> threads = new ArrayList<>();
        for (int i = 0; i < instances; i++) {
            JavaAnalyticsConsumer c = new JavaAnalyticsConsumer(bootstrap, jdbc, user, password, TOPIC, group);
            consumers.add(c);
            Thread t = new Thread(c, "java-consumer-" + i);
            t.start();
            threads.add(t);
        }
        double rate = measure(jdbc, user, password, relevant);
        consumers.forEach(JavaAnalyticsConsumer::stop);
        for (Thread t : threads) {
            t.join(10_000);
        }
        return rate;
    }

    private static double runGo(String binary, String bootstrap, String pgUrl, String jdbc, String user,
                                String password, int instances, int relevant) throws Exception {
        truncate(jdbc, user, password);
        String group = "bench-go-" + UUID.randomUUID();
        List<Process> procs = new ArrayList<>();
        for (int i = 0; i < instances; i++) {
            ProcessBuilder pb = new ProcessBuilder(new File(binary).getAbsolutePath());
            Map<String, String> env = pb.environment();
            env.put("KAFKA_BROKERS", bootstrap);
            env.put("KAFKA_TOPIC", TOPIC);
            env.put("KAFKA_GROUP", group);
            env.put("DATABASE_URL", pgUrl);
            env.put("METRICS_ADDR", ":" + (9110 + i));
            pb.redirectErrorStream(true).redirectOutput(new File("target/bench-go-" + i + ".log"));
            procs.add(pb.start());
        }
        try {
            return measure(jdbc, user, password, relevant);
        } finally {
            procs.forEach(Process::destroyForcibly);
            for (Process p : procs) {
                p.waitFor();
            }
        }
    }

    private static void truncate(String jdbc, String user, String password) throws Exception {
        try (Connection c = DriverManager.getConnection(jdbc, user, password); Statement s = c.createStatement()) {
            s.execute("TRUNCATE analytics_processed_events, order_window_stats");
        }
    }

    /** eventos/s entre o primeiro evento gravado e o ultimo (todos os relevantes processados). */
    private static double measure(String jdbc, String user, String password, int relevant) throws Exception {
        try (Connection c = DriverManager.getConnection(jdbc, user, password); Statement s = c.createStatement()) {
            long firstAt = -1;
            long firstCount = 0;
            long deadline = System.currentTimeMillis() + 15 * 60_000L;
            while (System.currentTimeMillis() < deadline) {
                long count;
                try (ResultSet rs = s.executeQuery("SELECT count(*) FROM analytics_processed_events")) {
                    rs.next();
                    count = rs.getLong(1);
                }
                long now = System.nanoTime();
                if (firstAt < 0 && count > 0) {
                    firstAt = now;
                    firstCount = count;
                }
                if (count >= relevant) {
                    double seconds = (now - firstAt) / 1e9;
                    return (relevant - firstCount) / Math.max(seconds, 0.001);
                }
                Thread.sleep(100);
            }
            throw new IllegalStateException("timeout aguardando o consumer drenar o topico");
        }
    }

    private static String arg(String[] args, String name, String def) {
        for (String a : args) {
            if (a.startsWith("--" + name + "=")) {
                return a.substring(name.length() + 3);
            }
        }
        return def;
    }
}
