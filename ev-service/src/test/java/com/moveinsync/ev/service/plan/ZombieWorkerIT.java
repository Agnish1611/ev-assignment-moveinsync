package com.moveinsync.ev.service.plan;

import static org.assertj.core.api.Assertions.assertThat;

import com.moveinsync.ev.domain.tenant.TenantId;
import com.moveinsync.ev.service.bus.BusRecord;
import com.moveinsync.ev.service.bus.BusSettings;
import com.moveinsync.ev.service.bus.Envelope;
import com.moveinsync.ev.service.bus.EnvelopeCodec;
import com.moveinsync.ev.service.bus.KafkaMessageBus;
import com.moveinsync.ev.service.bus.TopicLayout;
import com.moveinsync.ev.service.bus.Topics;
import com.moveinsync.ev.service.repository.JobRepository;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.concurrent.locks.LockSupport;
import javax.sql.DataSource;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;
import org.postgresql.ds.PGSimpleDataSource;
import org.testcontainers.DockerClientFactory;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.Network;
import org.testcontainers.containers.wait.strategy.Wait;
import org.testcontainers.kafka.KafkaContainer;
import org.testcontainers.postgresql.PostgreSQLContainer;
import org.testcontainers.utility.MountableFile;

/** Pausing one worker lets the group rebalance. Its later publish loses the compare-and-set. */
class ZombieWorkerIT {

  private static final String TENANT = "tenant-zombie";
  private static final String SHARD = "shard-zombie";
  private static final Instant START = ValidFleet.START;

  @Test
  void aPausedWorkerLosesTheCompareAndSet() throws Exception {
    Path jar = jar();
    try (Network network = Network.newNetwork();
        PostgreSQLContainer postgres = database(network);
        KafkaContainer kafka = broker(network)) {
      postgres.start();
      kafka.start();
      DataSource source = source(postgres);
      Flyway.configure().dataSource(source).locations("classpath:db/migration").load().migrate();
      ScenarioCatalog.store(source, ValidFleet.input(TenantId.of(TENANT)), SHARD);
      TopicLayout.ensure(kafka.getBootstrapServers());
      GenericContainer<?> first = worker(network, postgres, "worker-a", jar);
      GenericContainer<?> second = worker(network, postgres, "worker-b", jar);
      first.start();
      second.start();
      boolean paused = false;
      GenericContainer<?> zombie = first;
      try (Connection lock = source.getConnection()) {
        lock.setAutoCommit(false);
        try (Statement held = lock.createStatement()) {
          held.execute("LOCK TABLE vehicle IN ACCESS EXCLUSIVE MODE");
        }
        storeJob(source);
        send(kafka.getBootstrapServers());
        String name = waiter(source);
        zombie = "worker-b".equals(name) ? second : first;
        DockerClientFactory.instance().client().pauseContainerCmd(zombie.getContainerId()).exec();
        paused = true;
        lock.rollback();
        until(source, 1L, 30_000_000_000L);
        DockerClientFactory.instance().client().unpauseContainerCmd(zombie.getContainerId()).exec();
        paused = false;
        LockSupport.parkNanos(8_000_000_000L);
        assertThat(version(source)).isEqualTo(1L);
        assertThat(versions(source)).isEqualTo(1L);
      } finally {
        if (paused) {
          DockerClientFactory.instance()
              .client()
              .unpauseContainerCmd(zombie.getContainerId())
              .exec();
        }
        first.stop();
        second.stop();
      }
    }
  }

  private static String waiter(DataSource source) throws SQLException {
    long deadline = System.nanoTime() + 20_000_000_000L;
    while (System.nanoTime() < deadline) {
      String name =
          text(
              source,
              "SELECT application_name FROM pg_stat_activity "
                  + "WHERE application_name LIKE 'worker-%' AND wait_event_type = 'Lock'");
      if (!name.isEmpty()) {
        return name;
      }
      LockSupport.parkNanos(50_000_000L);
    }
    throw new IllegalStateException("no worker waited on the vehicle lock");
  }

  private static void until(DataSource source, long wanted, long nanos) {
    long deadline = System.nanoTime() + nanos;
    while (System.nanoTime() < deadline) {
      if (version(source) == wanted) {
        return;
      }
      LockSupport.parkNanos(50_000_000L);
    }
    throw new IllegalStateException("head did not reach " + wanted);
  }

  private static void send(String bootstrap) {
    KafkaMessageBus bus =
        new KafkaMessageBus(bootstrap, "zombie-send", BusSettings.standard(), Clock.systemUTC());
    try {
      Envelope envelope =
          new Envelope(
              "job-zombie",
              TENANT,
              "plan-api",
              1L,
              START,
              Envelope.SCHEMA_VERSION,
              new Envelope.PlanRequested(SHARD, "job-zombie", Envelope.PlanRequested.INITIAL));
      bus.send(
          BusRecord.of(Topics.PLAN_TRIGGERS, TENANT + ":" + SHARD, EnvelopeCodec.write(envelope)));
    } finally {
      bus.close();
    }
  }

  private static void storeJob(DataSource source) throws SQLException {
    try (Connection connection = source.getConnection()) {
      connection.setAutoCommit(false);
      new JobRepository().store(connection, TENANT, "job-zombie", SHARD, START, "INITIAL");
      connection.commit();
    }
  }

  private static GenericContainer<?> worker(
      Network network, PostgreSQLContainer postgres, String name, Path jar) {
    String url =
        "jdbc:postgresql://postgres:5432/"
            + postgres.getDatabaseName()
            + "?ApplicationName="
            + name;
    return new GenericContainer<>("eclipse-temurin:21-jre-jammy")
        .withNetwork(network)
        .withCopyFileToContainer(MountableFile.forHostPath(jar), "/app/app.jar")
        .withCommand("java", "-jar", "/app/app.jar")
        .withEnv("SPRING_DATASOURCE_URL", url)
        .withEnv("SPRING_DATASOURCE_USERNAME", "ev_worker")
        .withEnv("SPRING_DATASOURCE_PASSWORD", "ev_password")
        .withEnv("SPRING_FLYWAY_ENABLED", "false")
        .withEnv("EV_SCHEMA_MIGRATE", "false")
        .withEnv("EV_ROLES", "worker")
        .withEnv("EV_BUS_BOOTSTRAP", "kafka:19092")
        .withEnv("EV_BUS_GROUP", "zombie-it")
        .withEnv("EV_JOB_LEASE_SECONDS", "2")
        .withEnv("EV_JOB_REAP_MILLIS", "1000")
        .withEnv("SPRING_SECURITY_OAUTH2_RESOURCESERVER_JWT_JWK_SET_URI", "http://127.0.0.1:9/jwks")
        .withExposedPorts(8080)
        .waitingFor(
            Wait.forHttp("/actuator/health")
                .forPort(8080)
                .forStatusCode(200)
                .withStartupTimeout(Duration.ofSeconds(90)));
  }

  private static PostgreSQLContainer database(Network network) {
    return new PostgreSQLContainer("postgres:16-alpine")
        .withNetwork(network)
        .withNetworkAliases("postgres");
  }

  private static KafkaContainer broker(Network network) {
    return new KafkaContainer("apache/kafka-native:3.8.0")
        .withNetwork(network)
        .withNetworkAliases("kafka")
        .withListener("kafka:19092")
        .withEnv("KAFKA_AUTO_CREATE_TOPICS_ENABLE", "false");
  }

  private static DataSource source(PostgreSQLContainer postgres) {
    PGSimpleDataSource source = new PGSimpleDataSource();
    source.setURL(postgres.getJdbcUrl());
    source.setUser(postgres.getUsername());
    source.setPassword(postgres.getPassword());
    return source;
  }

  private static Path jar() {
    Path nested = Path.of("target", "ev-service-1.0.0-SNAPSHOT-exec.jar");
    if (Files.isRegularFile(nested)) {
      return nested.toAbsolutePath();
    }
    Path root = Path.of("ev-service", "target", "ev-service-1.0.0-SNAPSHOT-exec.jar");
    if (Files.isRegularFile(root)) {
      return root.toAbsolutePath();
    }
    throw new IllegalStateException("service jar is missing");
  }

  private static long version(DataSource source) {
    return count(source, "SELECT version FROM plan_head WHERE tenant_id = ? AND shard = ?");
  }

  private static long versions(DataSource source) {
    return count(source, "SELECT count(*) FROM plan_version WHERE tenant_id = ? AND shard = ?");
  }

  private static long count(DataSource source, String sql) {
    try (Connection connection = source.getConnection();
        PreparedStatement statement = connection.prepareStatement(sql)) {
      statement.setString(1, TENANT);
      statement.setString(2, SHARD);
      try (ResultSet rows = statement.executeQuery()) {
        if (!rows.next()) {
          return 0L;
        }
        return rows.getLong(1);
      }
    } catch (SQLException ex) {
      throw new IllegalStateException("query failed", ex);
    }
  }

  private static String text(DataSource source, String sql) throws SQLException {
    try (Connection connection = source.getConnection();
        Statement statement = connection.createStatement();
        ResultSet rows = statement.executeQuery(sql)) {
      if (!rows.next() || rows.getString(1) == null) {
        return "";
      }
      return rows.getString(1);
    }
  }
}
