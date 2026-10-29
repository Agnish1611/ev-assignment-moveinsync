package com.moveinsync.ev.planner.architecture;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;

import com.tngtech.archunit.core.importer.ImportOption;
import com.tngtech.archunit.junit.AnalyzeClasses;
import com.tngtech.archunit.junit.ArchTest;
import com.tngtech.archunit.lang.ArchRule;

/**
 * ArchUnit boundary tests enforcing pure Java isolation of ev-planner-core. As mandated by
 * AGENTS.md Section 2.2: ev-planner-core MUST NOT import Spring, JPA, Kafka, Jackson annotations or
 * any I/O library. Core algorithms are plain Java, testable without a web server or database.
 */
@AnalyzeClasses(
    packages = "com.moveinsync.ev.planner",
    importOptions = {ImportOption.DoNotIncludeTests.class})
public class PlannerArchitectureTest {

  @ArchTest
  public static final ArchRule plannerMustNeverImportFrameworks =
      noClasses()
          .that()
          .resideInAPackage("com.moveinsync.ev.planner..")
          .should()
          .dependOnClassesThat()
          .resideInAnyPackage(
              "org.springframework..",
              "jakarta.persistence..",
              "org.apache.kafka..",
              "com.fasterxml.jackson..",
              "java.sql..",
              "javax.sql..")
          .because("ev-planner-core MUST be pure Java without framework, DB, or I/O dependencies");

  @ArchTest
  public static final ArchRule plannerMustNeverImportValidator =
      noClasses()
          .that()
          .resideInAPackage("com.moveinsync.ev.planner..")
          .should()
          .dependOnClassesThat()
          .resideInAPackage("com.moveinsync.ev.validator..")
          .because("ev-planner-core MUST NOT depend on ev-validator");
}
