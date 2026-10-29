package com.moveinsync.ev.validator.architecture;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.classes;
import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;

import com.tngtech.archunit.core.importer.ImportOption;
import com.tngtech.archunit.junit.AnalyzeClasses;
import com.tngtech.archunit.junit.ArchTest;
import com.tngtech.archunit.lang.ArchRule;

/**
 * ArchUnit boundary tests enforcing strict architectural isolation of ev-validator. As mandated by
 * AGENTS.md Section 1 and ARCHITECTURE.md Section 2: ev-validator depends ONLY on ev-domain. It
 * MUST NOT import anything from ev-planner-core or ev-service, and MUST NOT share helper code with
 * the planner.
 */
@AnalyzeClasses(
    packages = "com.moveinsync.ev.validator",
    importOptions = {ImportOption.DoNotIncludeTests.class})
public class ValidatorArchitectureTest {

  @ArchTest
  public static final ArchRule validatorMustNeverImportPlannerCore =
      noClasses()
          .that()
          .resideInAPackage("com.moveinsync.ev.validator..")
          .should()
          .dependOnClassesThat()
          .resideInAPackage("com.moveinsync.ev.planner..")
          .because(
              "ev-validator MUST NOT depend on ev-planner-core (independent validator invariant)");

  @ArchTest
  public static final ArchRule validatorMustNeverImportService =
      noClasses()
          .that()
          .resideInAPackage("com.moveinsync.ev.validator..")
          .should()
          .dependOnClassesThat()
          .resideInAPackage("com.moveinsync.ev.service..")
          .because("ev-validator MUST NOT depend on ev-service");

  @ArchTest
  public static final ArchRule validatorMustNeverImportFrameworks =
      noClasses()
          .that()
          .resideInAPackage("com.moveinsync.ev.validator..")
          .should()
          .dependOnClassesThat()
          .resideInAnyPackage(
              "org.springframework..",
              "jakarta.persistence..",
              "org.apache.kafka..",
              "com.fasterxml.jackson..")
          .because("ev-validator MUST be pure Java without framework dependencies");

  @ArchTest
  public static final ArchRule validatorMayOnlyDependOnDomainAndStandardLibs =
      classes()
          .that()
          .resideInAPackage("com.moveinsync.ev.validator..")
          .should()
          .onlyDependOnClassesThat()
          .resideInAnyPackage(
              "com.moveinsync.ev.validator..",
              "com.moveinsync.ev.domain..",
              "java..",
              "org.slf4j..")
          .because("ev-validator depends ONLY on ev-domain and standard Java/logging libraries");
}
