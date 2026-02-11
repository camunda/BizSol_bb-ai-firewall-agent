package io.camunda.bizsol.bb.ai_firewall_agent.architecture;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.classes;
import static com.tngtech.archunit.library.Architectures.layeredArchitecture;

import com.tngtech.archunit.core.domain.JavaClasses;
import com.tngtech.archunit.core.importer.ClassFileImporter;
import com.tngtech.archunit.core.importer.ImportOption;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

class ArchitectureTest {

    private static JavaClasses classes;

    @BeforeAll
    static void importClasses() {
        classes =
                new ClassFileImporter()
                        .withImportOption(ImportOption.Predefined.DO_NOT_INCLUDE_TESTS)
                        .importPackages("io.camunda.bizsol.bb.ai_firewall_agent");
    }

    @Test
    void workersShouldOnlyDependOnServicesAndModels() {
        classes()
                .that()
                .resideInAPackage("..workers..")
                .should()
                .onlyDependOnClassesThat()
                .resideInAnyPackage(
                        "..workers..",
                        "..services..",
                        "..models..",
                        "java..",
                        "org.springframework..",
                        "io.camunda..",
                        "org.slf4j..")
                .check(classes);
    }

    @Test
    void servicesShouldOnlyDependOnModels() {
        classes()
                .that()
                .resideInAPackage("..services..")
                .should()
                .onlyDependOnClassesThat()
                .resideInAnyPackage(
                        "..services..",
                        "..models..",
                        "java..",
                        "org.springframework..",
                        "org.slf4j..")
                .check(classes);
    }

    @Test
    void modelsShouldNotDependOnWorkersOrServices() {
        classes()
                .that()
                .resideInAPackage("..models..")
                .should()
                .onlyDependOnClassesThat()
                .resideInAnyPackage("..models..", "java..", "com.fasterxml.jackson..")
                .check(classes);
    }

    @Test
    void layeredArchitectureShouldBeRespected() {
        layeredArchitecture()
                .consideringAllDependencies()
                .layer("Workers")
                .definedBy("..workers..")
                .layer("Services")
                .definedBy("..services..")
                .layer("Models")
                .definedBy("..models..")
                .layer("Config")
                .definedBy("..config..")
                .whereLayer("Workers")
                .mayNotBeAccessedByAnyLayer()
                .whereLayer("Services")
                .mayOnlyBeAccessedByLayers("Workers", "Config")
                .whereLayer("Models")
                .mayOnlyBeAccessedByLayers("Workers", "Services", "Config")
                .check(classes);
    }

    @Test
    void workersShouldBeAnnotatedWithComponent() {
        classes()
                .that()
                .resideInAPackage("..workers..")
                .and()
                .haveSimpleNameStartingWith("Worker")
                .should()
                .beAnnotatedWith(org.springframework.stereotype.Component.class)
                .allowEmptyShould(true)
                .check(classes);
    }

    @Test
    void servicesShouldBeAnnotatedWithService() {
        classes()
                .that()
                .resideInAPackage("..services..")
                .and()
                .haveSimpleNameStartingWith("Service")
                .should()
                .beAnnotatedWith(org.springframework.stereotype.Service.class)
                .allowEmptyShould(true)
                .check(classes);
    }
}
