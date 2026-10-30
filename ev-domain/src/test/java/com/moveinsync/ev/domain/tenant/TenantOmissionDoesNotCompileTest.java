package com.moveinsync.ev.domain.tenant;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;
import javax.tools.DiagnosticCollector;
import javax.tools.JavaCompiler;
import javax.tools.JavaFileObject;
import javax.tools.SimpleJavaFileObject;
import javax.tools.StandardLocation;
import javax.tools.ToolProvider;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** Proves ground-truth constructors and factories cannot omit {@link TenantId}. */
class TenantOmissionDoesNotCompileTest {

  private static final String VEHICLE_WITH_TENANT =
      """
      import com.moveinsync.ev.domain.tenant.TenantId;
      import com.moveinsync.ev.domain.vehicle.Vehicle;
      import com.moveinsync.ev.domain.vehicle.VehicleId;
      class KeepVehicle {
        void v() {
          new Vehicle(TenantId.of("tenant-compile"), VehicleId.of("CAB"), 60000L, 150L, 50);
        }
      }
      """;

  private static final String VEHICLE_WITHOUT_TENANT =
      """
      import com.moveinsync.ev.domain.vehicle.Vehicle;
      import com.moveinsync.ev.domain.vehicle.VehicleId;
      class OmitVehicle {
        void v() { new Vehicle(VehicleId.of("CAB"), 60000L, 150L, 50); }
      }
      """;

  private static final String TRIP_WITHOUT_TENANT =
      """
      import com.moveinsync.ev.domain.location.Location;
      import com.moveinsync.ev.domain.trip.Trip;
      import com.moveinsync.ev.domain.trip.TripId;
      import java.time.Instant;
      class OmitTrip {
        void v() {
          new Trip(
              TripId.of("T"),
              Instant.EPOCH,
              Location.of("A", "A"),
              Location.of("B", "B"),
              1000,
              60,
              Instant.EPOCH.plusSeconds(120),
              "EMP");
        }
      }
      """;

  private static final String HUB_OF_WITHOUT_TENANT =
      """
      import com.moveinsync.ev.domain.hub.Hub;
      import com.moveinsync.ev.domain.location.Location;
      import java.time.ZoneId;
      class OmitHub {
        void v() {
          Hub.of("HUB-1", "Hub", Location.of("L", "L"), ZoneId.of("UTC"), 1);
        }
      }
      """;

  private static final String CHARGER_OF_WITHOUT_TENANT =
      """
      import com.moveinsync.ev.domain.hub.Charger;
      class OmitChargerOf {
        void v() { Charger.of("CHG-1", "HUB-1", 50); }
      }
      """;

  private static final String CHARGER_CTOR_WITHOUT_TENANT =
      """
      import com.moveinsync.ev.domain.hub.Charger;
      import com.moveinsync.ev.domain.hub.ChargerId;
      import com.moveinsync.ev.domain.hub.ChargerStatus;
      import com.moveinsync.ev.domain.hub.HubId;
      class OmitChargerCtor {
        void v() {
          new Charger(ChargerId.of("CHG-1"), HubId.of("HUB-1"), 50, ChargerStatus.OPERATIONAL);
        }
      }
      """;

  @Test
  @DisplayName("Omitting TenantId on Vehicle, Trip, Hub, and Charger does not compile")
  void omittingTenantDoesNotCompile() {
    assertThat(compile(VEHICLE_WITH_TENANT)).isNull();
    assertRejected(VEHICLE_WITHOUT_TENANT);
    assertRejected(TRIP_WITHOUT_TENANT);
    assertRejected(HUB_OF_WITHOUT_TENANT);
    assertRejected(CHARGER_OF_WITHOUT_TENANT);
    assertRejected(CHARGER_CTOR_WITHOUT_TENANT);
  }

  private static void assertRejected(String source) {
    String errors = compile(source);
    assertThat(errors).isNotBlank();
    assertThat(errors).containsIgnoringCase("cannot be applied");
  }

  private static String compile(String source) {
    JavaCompiler compiler = ToolProvider.getSystemJavaCompiler();
    assertThat(compiler).as("JDK compiler required").isNotNull();
    DiagnosticCollector<JavaFileObject> diagnostics = new DiagnosticCollector<>();
    Path output = createOutputDir();
    try (var files = compiler.getStandardFileManager(diagnostics, null, null)) {
      files.setLocation(StandardLocation.CLASS_OUTPUT, List.of(output.toFile()));
      Boolean ok =
          compiler
              .getTask(
                  null,
                  files,
                  diagnostics,
                  List.of("-classpath", System.getProperty("java.class.path")),
                  null,
                  List.of(sourceFile(source)))
              .call();
      if (Boolean.TRUE.equals(ok)) {
        return null;
      }
    } catch (IOException ex) {
      throw new IllegalStateException("compiler file manager failed", ex);
    } finally {
      deleteTree(output);
    }
    return diagnostics.getDiagnostics().stream()
        .map(diagnostic -> diagnostic.getMessage(Locale.ENGLISH))
        .reduce("", (left, right) -> left + right + "\n");
  }

  private static Path createOutputDir() {
    try {
      return Files.createTempDirectory("tenant-omission-");
    } catch (IOException ex) {
      throw new IllegalStateException("compiler output directory failed", ex);
    }
  }

  private static void deleteTree(Path output) {
    if (!Files.exists(output)) {
      return;
    }
    try (Stream<Path> walk = Files.walk(output)) {
      walk.sorted(Comparator.reverseOrder()).forEach(TenantOmissionDoesNotCompileTest::deleteOne);
    } catch (IOException ex) {
      throw new IllegalStateException("compiler output cleanup failed", ex);
    }
  }

  private static void deleteOne(Path path) {
    try {
      Files.deleteIfExists(path);
    } catch (IOException ex) {
      throw new IllegalStateException("compiler output cleanup failed", ex);
    }
  }

  private static JavaFileObject sourceFile(String source) {
    String name = className(source);
    URI uri = URI.create("string:///" + name + ".java");
    return new SimpleJavaFileObject(uri, JavaFileObject.Kind.SOURCE) {
      @Override
      public CharSequence getCharContent(boolean ignoreEncodingErrors) {
        return source;
      }
    };
  }

  private static String className(String source) {
    Matcher matcher = Pattern.compile("class\\s+(\\w+)").matcher(source);
    if (!matcher.find()) {
      throw new IllegalArgumentException("snippet declares no class");
    }
    return matcher.group(1);
  }
}
