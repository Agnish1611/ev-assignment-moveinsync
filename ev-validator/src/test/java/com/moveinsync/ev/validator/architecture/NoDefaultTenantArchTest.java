package com.moveinsync.ev.validator.architecture;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.classes;

import com.tngtech.archunit.core.domain.JavaClass;
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

/** Bans the sentinel tenant string from validator production classes. */
@AnalyzeClasses(
    packages = "com.moveinsync.ev.validator",
    importOptions = {ImportOption.DoNotIncludeTests.class})
public class NoDefaultTenantArchTest {

  @ArchTest
  public static final ArchRule validatorMustNotEmbedDefaultTenant =
      classes()
          .that()
          .resideInAPackage("com.moveinsync.ev.validator..")
          .should(new NoExactUtf8())
          .because("src/main must not contain the sentinel tenant id");

  static final class NoExactUtf8 extends ArchCondition<JavaClass> {

    NoExactUtf8() {
      super("not contain the UTF-8 constant \"default\"");
    }

    @Override
    public void check(JavaClass item, ConditionEvents events) {
      String resource = item.getName().replace('.', '/') + ".class";
      ClassLoader loader = item.reflect().getClassLoader();
      try (InputStream in = loader.getResourceAsStream(resource)) {
        if (in != null && containsExactUtf8(in.readAllBytes(), "default")) {
          events.add(
              SimpleConditionEvent.violated(item, item.getName() + " contains forbidden string"));
        }
      } catch (IOException ex) {
        throw new IllegalStateException("failed to read " + resource, ex);
      }
    }

    private static boolean containsExactUtf8(byte[] classFile, String needle) {
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
}
