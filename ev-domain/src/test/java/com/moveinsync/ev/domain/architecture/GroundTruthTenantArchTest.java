package com.moveinsync.ev.domain.architecture;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.classes;

import com.moveinsync.ev.domain.tenant.TenantId;
import com.tngtech.archunit.core.domain.JavaClass;
import com.tngtech.archunit.core.domain.JavaConstructor;
import com.tngtech.archunit.core.domain.JavaMethod;
import com.tngtech.archunit.core.importer.ImportOption;
import com.tngtech.archunit.junit.AnalyzeClasses;
import com.tngtech.archunit.junit.ArchTest;
import com.tngtech.archunit.lang.ArchCondition;
import com.tngtech.archunit.lang.ArchRule;
import com.tngtech.archunit.lang.ConditionEvents;
import com.tngtech.archunit.lang.SimpleConditionEvent;
import java.io.IOException;
import java.io.InputStream;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;

/** Bans an implicit tenant on ground-truth types and the sentinel tenant string. */
@AnalyzeClasses(
    packages = "com.moveinsync.ev.domain",
    importOptions = {ImportOption.DoNotIncludeTests.class})
public class GroundTruthTenantArchTest {

  @ArchTest
  public static final ArchRule groundTruthTypesRequireTenant =
      classes()
          .that()
          .haveSimpleName("Vehicle")
          .or()
          .haveSimpleName("Trip")
          .or()
          .haveSimpleName("Hub")
          .or()
          .haveSimpleName("Charger")
          .should(new TenantBearingApi())
          .because("ground-truth factories must take an explicit tenant and must not default one");

  @ArchTest
  public static final ArchRule domainMustNotEmbedDefaultTenant =
      classes()
          .that()
          .resideInAPackage("com.moveinsync.ev.domain..")
          .should(new NoExactUtf8("default"))
          .because("src/main must not contain the sentinel tenant id");

  static final class TenantBearingApi extends ArchCondition<JavaClass> {

    TenantBearingApi() {
      super("expose only tenant-bearing constructors and factories");
    }

    @Override
    public void check(JavaClass item, ConditionEvents events) {
      for (JavaConstructor constructor : item.getConstructors()) {
        if (constructor.getRawParameterTypes().isEmpty()
            || !constructor.getRawParameterTypes().get(0).isEquivalentTo(TenantId.class)) {
          events.add(
              SimpleConditionEvent.violated(
                  constructor, constructor.getFullName() + " omits TenantId"));
        }
      }
      for (JavaMethod method : item.getMethods()) {
        if (!method.getOwner().equals(item) || !"of".equals(method.getName())) {
          continue;
        }
        int expected = "Hub".equals(item.getSimpleName()) ? 6 : 4;
        if (method.getRawParameterTypes().size() != expected) {
          events.add(
              SimpleConditionEvent.violated(
                  method, method.getFullName() + " omits the tenant parameter"));
        }
      }
    }
  }

  static final class NoExactUtf8 extends ArchCondition<JavaClass> {

    private final String forbidden;

    NoExactUtf8(String forbidden) {
      super("not contain the UTF-8 constant \"" + forbidden + "\"");
      this.forbidden = forbidden;
    }

    @Override
    public void check(JavaClass item, ConditionEvents events) {
      String resource = item.getName().replace('.', '/') + ".class";
      ClassLoader loader = item.reflect().getClassLoader();
      try (InputStream in = loader.getResourceAsStream(resource)) {
        if (in != null && containsExactUtf8(in.readAllBytes(), forbidden)) {
          events.add(
              SimpleConditionEvent.violated(
                  item, item.getName() + " contains forbidden string " + forbidden));
        }
      } catch (IOException ex) {
        throw new IllegalStateException("failed to read " + resource, ex);
      }
    }
  }

  static boolean containsExactUtf8(byte[] classFile, String needle) {
    ByteBuffer buf = ByteBuffer.wrap(classFile);
    if (buf.getInt() != 0xCAFEBABE) {
      return false;
    }
    buf.getShort();
    buf.getShort();
    int count = Short.toUnsignedInt(buf.getShort());
    int index = 1;
    while (index < count) {
      int tag = Byte.toUnsignedInt(buf.get());
      if (tag == 1) {
        int len = Short.toUnsignedInt(buf.getShort());
        byte[] data = new byte[len];
        buf.get(data);
        if (needle.equals(new String(data, StandardCharsets.UTF_8))) {
          return true;
        }
      } else if (tag == 7 || tag == 8 || tag == 16 || tag == 19 || tag == 20) {
        buf.getShort();
      } else if (tag == 15) {
        buf.get();
        buf.getShort();
      } else if (tag == 5 || tag == 6) {
        buf.getLong();
        index++;
      } else {
        buf.getInt();
      }
      index++;
    }
    return false;
  }
}
