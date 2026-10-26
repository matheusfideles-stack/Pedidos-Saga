package com.saga.testsupport;

import io.zonky.test.db.postgres.embedded.EmbeddedPostgres;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.kafka.test.EmbeddedKafkaKraftBroker;
import org.testcontainers.DockerClientFactory;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.kafka.KafkaContainer;
import org.testcontainers.utility.DockerImageName;

/**
 * Infra compartilhada pelos testes de integracao (um PostgreSQL e um Kafka por JVM).
 *
 * <ul>
 *   <li><b>Modo DOCKER</b> (padrao quando ha Docker): Testcontainers, PostgreSQL 16 com wal_level=logical
 *       e Kafka KRaft (apache/kafka).</li>
 *   <li><b>Modo EMBEDDED</b> (sem Docker): PostgreSQL nativo (Zonky) com wal_level=logical e broker
 *       Kafka KRaft embarcado (spring-kafka-test). Mesmos bancos, mesmo protocolo, mesma logica.</li>
 * </ul>
 * Force o modo com {@code -Dsaga.test.infra=docker|embedded}.
 * O CDC e sempre feito pelo conector real do Debezium (ver {@link CdcRelay}).
 */
public final class TestInfra {

    private static final Logger log = LoggerFactory.getLogger(TestInfra.class);
    private static TestInfra instance;

    public enum Mode { DOCKER, EMBEDDED }

    private final Mode mode;
    private final String pgHost;
    private final int pgPort;
    private final String pgUser;
    private final String pgPassword;
    private final String kafkaBootstrap;
    private final List<AutoCloseable> closeables = new ArrayList<>();

    public static synchronized TestInfra get() {
        if (instance == null) {
            instance = new TestInfra();
            Runtime.getRuntime().addShutdownHook(new Thread(instance::close, "test-infra-shutdown"));
        }
        return instance;
    }

    private TestInfra() {
        String forced = System.getProperty("saga.test.infra", "auto");
        boolean docker = switch (forced) {
            case "docker" -> true;
            case "embedded" -> false;
            default -> dockerAvailable();
        };
        try {
            if (docker) {
                mode = Mode.DOCKER;
                PostgreSQLContainer<?> pg = new PostgreSQLContainer<>("postgres:16-alpine")
                        .withCommand("postgres", "-c", "wal_level=logical", "-c", "max_replication_slots=20",
                                "-c", "max_wal_senders=20", "-c", "max_connections=300");
                pg.start();
                closeables.add(pg::stop);
                pgHost = pg.getHost();
                pgPort = pg.getMappedPort(5432);
                pgUser = pg.getUsername();
                pgPassword = pg.getPassword();

                KafkaContainer kafka = new KafkaContainer(DockerImageName.parse("apache/kafka:3.8.1"));
                kafka.start();
                closeables.add(kafka::stop);
                kafkaBootstrap = kafka.getBootstrapServers();
            } else {
                mode = Mode.EMBEDDED;
                EmbeddedPostgres pg = EmbeddedPostgres.builder()
                        .setServerConfig("wal_level", "logical")
                        .setServerConfig("max_replication_slots", "20")
                        .setServerConfig("max_wal_senders", "20")
                        .setServerConfig("max_connections", "300")
                        .start();
                closeables.add(pg);
                pgHost = "localhost";
                pgPort = pg.getPort();
                pgUser = "postgres";
                pgPassword = "postgres";

                EmbeddedKafkaKraftBroker kafka = new EmbeddedKafkaKraftBroker(1, Topics6.PARTITIONS);
                kafka.afterPropertiesSet();
                closeables.add(kafka::destroy);
                kafkaBootstrap = kafka.getBrokersAsString();
            }
        } catch (Exception e) {
            close();
            throw new IllegalStateException("falha ao subir a infra de testes", e);
        }
        log.info("TestInfra modo={} postgres={}:{} kafka={}", mode, pgHost, pgPort, kafkaBootstrap);
    }

    private static boolean dockerAvailable() {
        try {
            return DockerClientFactory.instance().isDockerAvailable();
        } catch (Throwable t) {
            return false;
        }
    }

    public Mode mode() {
        return mode;
    }

    private int redisPort = -1;

    /** Redis sob demanda (container no modo DOCKER, redis-server embarcado no modo EMBEDDED). */
    public synchronized int redisPort() {
        if (redisPort > 0) {
            return redisPort;
        }
        try {
            if (mode == Mode.DOCKER) {
                org.testcontainers.containers.GenericContainer<?> redis =
                        new org.testcontainers.containers.GenericContainer<>("redis:7-alpine").withExposedPorts(6379);
                redis.start();
                closeables.add(redis::stop);
                redisPort = redis.getMappedPort(6379);
            } else {
                int port;
                try (java.net.ServerSocket s = new java.net.ServerSocket(0)) {
                    port = s.getLocalPort();
                }
                redis.embedded.RedisServer server = redis.embedded.RedisServer.newRedisServer().port(port)
                        .setting("maxmemory 128M").build();
                server.start();
                closeables.add(server::stop);
                redisPort = port;
            }
        } catch (Exception e) {
            throw new IllegalStateException("falha ao subir o Redis de teste", e);
        }
        return redisPort;
    }

    public String redisHost() {
        return "localhost";
    }

    public String kafkaBootstrap() {
        return kafkaBootstrap;
    }

    public String pgHost() {
        return pgHost;
    }

    public int pgPort() {
        return pgPort;
    }

    public String pgUser() {
        return pgUser;
    }

    public String pgPassword() {
        return pgPassword;
    }

    /** Cria (se preciso) um banco isolado e devolve a URL JDBC. */
    public synchronized String createDatabase(String name) {
        String admin = "jdbc:postgresql://" + pgHost + ":" + pgPort + "/" + (mode == Mode.DOCKER ? "test" : "postgres");
        try (Connection c = DriverManager.getConnection(admin, pgUser, pgPassword);
             Statement s = c.createStatement()) {
            s.execute("CREATE DATABASE " + name);
        } catch (Exception e) {
            if (!String.valueOf(e.getMessage()).contains("already exists")) {
                throw new IllegalStateException(e);
            }
        }
        return jdbcUrl(name);
    }

    public String jdbcUrl(String database) {
        return "jdbc:postgresql://" + pgHost + ":" + pgPort + "/" + database;
    }

    /** Inicia o Debezium (CDC da tabela outbox) para o banco e publica no Kafka. */
    public CdcRelay startCdc(String database) {
        CdcRelay relay = new CdcRelay(this, database);
        relay.start();
        closeables.add(relay);
        awaitReplicationSlotActive(database);
        return relay;
    }

    /** Eventos gravados antes do slot estar ativo nao seriam capturados (snapshot desligado). */
    private void awaitReplicationSlotActive(String database) {
        long deadline = System.currentTimeMillis() + 60_000;
        try (Connection c = DriverManager.getConnection(jdbcUrl(database), pgUser, pgPassword)) {
            while (System.currentTimeMillis() < deadline) {
                try (Statement s = c.createStatement();
                     var rs = s.executeQuery("SELECT 1 FROM pg_replication_slots WHERE slot_name = 'slot_"
                             + database + "' AND active")) {
                    if (rs.next()) {
                        Thread.sleep(500); // streaming iniciado
                        return;
                    }
                }
                Thread.sleep(200);
            }
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
        throw new IllegalStateException("slot de replicacao nao ficou ativo para " + database);
    }

    public synchronized void close() {
        for (int i = closeables.size() - 1; i >= 0; i--) {
            try {
                closeables.get(i).close();
            } catch (Exception ignored) {
                // melhor esforco no shutdown
            }
        }
        closeables.clear();
    }

    /** Evita depender do modulo common neste modulo de suporte. */
    private static final class Topics6 {
        static final int PARTITIONS = 6;
    }
}
