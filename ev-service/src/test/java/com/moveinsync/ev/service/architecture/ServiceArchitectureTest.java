package com.moveinsync.ev.service.architecture;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;

import com.tngtech.archunit.core.importer.ImportOption;
import com.tngtech.archunit.junit.AnalyzeClasses;
import com.tngtech.archunit.junit.ArchTest;
import com.tngtech.archunit.lang.ArchRule;

/**
 * ArchUnit layering tests for ev-service. As mandated by AGENTS.md Section 2.2: Layering:
 * controller -> service -> repository. Controllers contain no business logic and no repository
 * access.
 */
@AnalyzeClasses(
    packages = "com.moveinsync.ev.service",
    importOptions = {ImportOption.DoNotIncludeTests.class})
public class ServiceArchitectureTest {

  @ArchTest
  public static final ArchRule controllersMustNotAccessRepositoriesDirectly =
      noClasses()
          .that()
          .resideInAPackage("com.moveinsync.ev.service.controller..")
          .should()
          .dependOnClassesThat()
          .resideInAPackage("com.moveinsync.ev.service.repository..")
          .because("Controllers must access repositories only via services")
          .allowEmptyShould(true);
}
