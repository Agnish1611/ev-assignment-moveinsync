package com.moveinsync.ev.domain.architecture;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.classes;
import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;

import com.tngtech.archunit.core.importer.ImportOption;
import com.tngtech.archunit.junit.AnalyzeClasses;
import com.tngtech.archunit.junit.ArchTest;
import com.tngtech.archunit.lang.ArchRule;

/**
 * ArchUnit boundary tests enforcing strict architectural purity of ev-domain.
 *
 * <p>As mandated by AGENTS.md Section 2.2: ev-domain MUST NOT import Spring, JPA, Kafka, Jackson
 * annotations or any I/O library. Pure domain models are plain Java, testable without a web server
 * or database.
 */
@AnalyzeClasses(
    packages = "com.moveinsync.ev.domain",
    importOptions = {ImportOption.DoNotIncludeTests.class})
public class DomainArchitectureTest {

  @ArchTest
  public static final ArchRule domainMustNeverImportFrameworks =
      noClasses()
          .that()
          .resideInAPackage("com.moveinsync.ev.domain..")
          .should()
          .dependOnClassesThat()
          .resideInAnyPackage(
              "org.springframework..",
              "jakarta.persistence..",
              "org.apache.kafka..",
              "com.fasterxml.jackson..",
              "java.sql..",
              "javax.sql..")
          .because("ev-domain MUST be pure Java without framework, DB, or I/O dependencies");

  @ArchTest
  public static final ArchRule domainMustNeverImportOtherModules =
      noClasses()
          .that()
          .resideInAPackage("com.moveinsync.ev.domain..")
          .should()
          .dependOnClassesThat()
          .resideInAnyPackage(
              "com.moveinsync.ev.planner..",
              "com.moveinsync.ev.validator..",
              "com.moveinsync.ev.service..",
              "com.moveinsync.ev.sim..",
              "com.moveinsync.ev.bench..")
          .because("ev-domain is the root domain model and must never depend on higher modules");

  @ArchTest
  public static final ArchRule domainMayOnlyDependOnJavaStandardLibrary =
      classes()
          .that()
          .resideInAPackage("com.moveinsync.ev.domain..")
          .should()
          .onlyDependOnClassesThat()
          .resideInAnyPackage("com.moveinsync.ev.domain..", "java..")
          .because("ev-domain classes must only depend on ev-domain and java standard library");
}
