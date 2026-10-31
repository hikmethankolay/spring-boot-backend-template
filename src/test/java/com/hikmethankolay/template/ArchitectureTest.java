package com.hikmethankolay.template;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;
import static com.tngtech.archunit.library.GeneralCodingRules.NO_CLASSES_SHOULD_ACCESS_STANDARD_STREAMS;
import static com.tngtech.archunit.library.GeneralCodingRules.NO_CLASSES_SHOULD_USE_FIELD_INJECTION;
import static com.tngtech.archunit.library.GeneralCodingRules.NO_CLASSES_SHOULD_USE_JAVA_UTIL_LOGGING;

import com.tngtech.archunit.core.importer.ImportOption;
import com.tngtech.archunit.junit.AnalyzeClasses;
import com.tngtech.archunit.junit.ArchTest;
import com.tngtech.archunit.lang.ArchRule;
import jakarta.persistence.Entity;
import org.springframework.data.repository.Repository;
import org.springframework.web.bind.annotation.RestController;

/**
 * Architecture rules, checked on every build. They keep new features consistent with the template:
 * controllers stay thin and talk to services, entities never leak into the web layer, and
 * dependencies are injected through constructors.
 */
@AnalyzeClasses(packages = "com.hikmethankolay.template", importOptions = ImportOption.DoNotIncludeTests.class)
class ArchitectureTest {

    @ArchTest
    static final ArchRule controllersDoNotUseRepositories = noClasses()
            .that()
            .areAnnotatedWith(RestController.class)
            .should()
            .dependOnClassesThat()
            .areAssignableTo(Repository.class)
            .because("controllers call services; services own data access and transactions");

    @ArchTest
    static final ArchRule controllersDoNotExposeEntities = noClasses()
            .that()
            .areAnnotatedWith(RestController.class)
            .should()
            .dependOnClassesThat()
            .areAnnotatedWith(Entity.class)
            .because("the API speaks DTOs, so entities can change without breaking clients");

    @ArchTest
    static final ArchRule nothingDependsOnControllers = noClasses()
            .that()
            .areNotAnnotatedWith(RestController.class)
            .should()
            .dependOnClassesThat()
            .areAnnotatedWith(RestController.class)
            .because("controllers are entry points, not reusable components");

    /** The application's root package, so the rule below survives scripts/rename-project.sh. */
    private static final String ROOT = ArchitectureTest.class.getPackageName();

    @ArchTest
    static final ArchRule sharedPackagesDependOnNoFeature = noClasses()
            .that()
            .resideInAnyPackage(ROOT + ".audit..", ROOT + ".common..")
            .should()
            .dependOnClassesThat()
            .resideInAnyPackage(
                    ROOT + ".account..",
                    ROOT + ".auth..",
                    ROOT + ".mail..",
                    ROOT + ".mfa..",
                    ROOT + ".note..",
                    ROOT + ".security..",
                    ROOT + ".social..",
                    ROOT + ".user..")
            .because("every feature uses audit and common, so they must not use a feature back (no cycles); put "
                    + "endpoints that read them in the feature that owns the data");

    @ArchTest
    static final ArchRule noFieldInjection = NO_CLASSES_SHOULD_USE_FIELD_INJECTION;

    @ArchTest
    static final ArchRule noStandardStreams = NO_CLASSES_SHOULD_ACCESS_STANDARD_STREAMS;

    @ArchTest
    static final ArchRule noJavaUtilLogging = NO_CLASSES_SHOULD_USE_JAVA_UTIL_LOGGING;
}
