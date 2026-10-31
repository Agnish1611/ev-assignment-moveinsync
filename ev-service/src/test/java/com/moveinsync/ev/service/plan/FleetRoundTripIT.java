package com.moveinsync.ev.service.plan;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.moveinsync.ev.domain.vehicle.VehicleId;
import com.moveinsync.ev.planner.input.PlannerInput;
import com.moveinsync.ev.service.fleet.FleetLoad;
import com.moveinsync.ev.service.fleet.FleetRejected;
import com.moveinsync.ev.service.fleet.LoadedFleet;
import com.moveinsync.ev.sim.ScaleScenario;
import com.moveinsync.ev.validator.ValidationInput;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.sql.Timestamp;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

/** The evening fleet survives a database round trip into both inputs. */
class FleetRoundTripIT {

  private static final String SHARD = "shard-scale";
  private static PlannerInput source;

  @BeforeAll
  static void storeScaleFleet() throws SQLException {
    source = ScaleScenario.build();
    ScenarioCatalog.store(PlanDatabase.open(), source, SHARD);
  }

  @Test
  void eveningFleetMatchesPlannerAndValidationInputs() {
    LoadedFleet loaded = new FleetLoad(PlanDatabase.open()).load(source.tenantId().value(), SHARD);
    assertThat(loaded.catalogVersion()).isEqualTo(1L);
    assertThat(loaded.planner().inputHash()).isEqualTo(source.inputHash());
    assertThat(loaded.planner().cabs()).hasSize(ScaleScenario.CAB_COUNT);
    assertThat(loaded.planner().trips()).hasSize(ScaleScenario.TRIP_COUNT);
    assertThat(loaded.planner().chargers()).hasSize(ScaleScenario.CHARGER_COUNT);
    ValidationInput validation = loaded.validation();
    assertThat(validation.trips()).hasSize(ScaleScenario.TRIP_COUNT);
    assertThat(validation.vehicles()).hasSize(ScaleScenario.CAB_COUNT);
    assertThat(validation.initialSocsWh().get(VehicleId.of("CAB-000"))).isEqualTo(50_000L);
    assertThat(validation.batteryFloorPercentage()).isEqualTo(15);
    assertThat(validation.initialLocations().get(VehicleId.of("CAB-000")).latitudeMicros())
        .isEqualTo(source.cabs().get(0).startLocation().latitudeMicros());
    assertThat(loaded.planner().trips().get(0).distanceMeters()).isEqualTo(2_000);
    assertThat(loaded.planner().trips().get(0).estimatedDurationSeconds()).isEqualTo(600);
  }

  @Test
  void anotherShardStaysOutOfTheLoadedFleet() throws SQLException {
    storeForeignShard();
    LoadedFleet loaded = new FleetLoad(PlanDatabase.open()).load(source.tenantId().value(), SHARD);
    assertThat(loaded.planner().cabs()).hasSize(ScaleScenario.CAB_COUNT);
    assertThat(loaded.planner().trips()).hasSize(ScaleScenario.TRIP_COUNT);
  }

  @Test
  void missingCoordinatesRejectTheFleet() throws SQLException {
    storeIncomplete();
    assertThatThrownBy(
            () -> new FleetLoad(PlanDatabase.open()).load("tenant-incomplete", "shard-bad"))
        .isInstanceOf(FleetRejected.class)
        .extracting(error -> ((FleetRejected) error).code())
        .isEqualTo("MISSING_COORDINATES");
  }

  private static void storeForeignShard() throws SQLException {
    try (Connection connection = PlanDatabase.open().getConnection()) {
      connection.setAutoCommit(false);
      try (PreparedStatement statement =
          connection.prepareStatement(
              "INSERT INTO shard (tenant_id, shard_id, horizon_start, horizon_end)"
                  + " VALUES (?, 'shard-other', ?, ?) ON CONFLICT DO NOTHING")) {
        statement.setString(1, source.tenantId().value());
        statement.setTimestamp(2, Timestamp.from(ScaleScenario.START));
        statement.setTimestamp(3, Timestamp.from(ScaleScenario.END));
        statement.executeUpdate();
      }
      try (PreparedStatement statement =
          connection.prepareStatement(
              "INSERT INTO vehicle (tenant_id, vehicle_id, battery_wh, consumption_wh_per_km,"
                  + " max_charge_kw, shard_id) VALUES (?, 'CAB-OTHER', 60000, 150, 50, 'shard-other')"
                  + " ON CONFLICT DO NOTHING")) {
        statement.setString(1, source.tenantId().value());
        statement.executeUpdate();
      }
      try (PreparedStatement statement =
          connection.prepareStatement(
              "INSERT INTO trip (tenant_id, trip_id, start_ts, from_loc, to_loc, distance_m,"
                  + " latest_finish_ts, employee_ref, shard_id)"
                  + " VALUES (?, 'TRIP-OTHER', ?, 'LOC-P', 'LOC-D', 2000, ?, 'EMP-OTHER',"
                  + " 'shard-other') ON CONFLICT DO NOTHING")) {
        statement.setString(1, source.tenantId().value());
        statement.setTimestamp(2, Timestamp.from(ScaleScenario.START.plusSeconds(600)));
        statement.setTimestamp(3, Timestamp.from(ScaleScenario.START.plusSeconds(1200)));
        statement.executeUpdate();
      }
      connection.commit();
    }
  }

  private static void storeIncomplete() throws SQLException {
    try (Connection connection = PlanDatabase.open().getConnection()) {
      connection.setAutoCommit(false);
      execute(
          connection,
          "INSERT INTO tenant (tenant_id) VALUES ('tenant-incomplete') ON CONFLICT DO NOTHING");
      execute(
          connection,
          "INSERT INTO catalog_version (tenant_id, version) VALUES ('tenant-incomplete', 1)"
              + " ON CONFLICT DO NOTHING");
      try (PreparedStatement statement =
          connection.prepareStatement(
              "INSERT INTO shard (tenant_id, shard_id, horizon_start, horizon_end)"
                  + " VALUES ('tenant-incomplete', 'shard-bad', ?, ?) ON CONFLICT DO NOTHING")) {
        statement.setTimestamp(1, Timestamp.from(ScaleScenario.START));
        statement.setTimestamp(2, Timestamp.from(ScaleScenario.END));
        statement.executeUpdate();
      }
      execute(
          connection,
          "INSERT INTO fleet_location (tenant_id, location_id, name, latitude_micros, longitude_micros)"
              + " VALUES ('tenant-incomplete', 'LOC-BARE', 'Bare', NULL, NULL) ON CONFLICT DO NOTHING");
      execute(
          connection,
          "INSERT INTO hub (tenant_id, hub_id, name, location_id, zone_id, charger_count)"
              + " VALUES ('tenant-incomplete', 'HUB-1', 'Hub', 'LOC-BARE', 'UTC', 1)"
              + " ON CONFLICT DO NOTHING");
      connection.commit();
    }
  }

  private static void execute(Connection connection, String sql) throws SQLException {
    try (PreparedStatement statement = connection.prepareStatement(sql)) {
      statement.executeUpdate();
    }
  }
}
