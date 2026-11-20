package com.adrianrevilla.trading.architecture;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.classes;
import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;
import static com.tngtech.archunit.library.Architectures.layeredArchitecture;
import static com.tngtech.archunit.library.dependencies.SlicesRuleDefinition.slices;

import com.tngtech.archunit.lang.ArchRule;

/**
 * The architecture of the bot as executable rules (ADR-0025): hexagonal, with the domain at the centre.
 *
 * <pre>
 *   adapter ──► application ──► domain          (the arrow is "may depend on"; never the other way)
 *   app (boot) wires everything and nothing depends on it
 * </pre>
 *
 * Every rule says why it exists. A rule is never relaxed to make the build pass: the code is fixed, or a new ADR
 * changes the rule. {@code ArchitectureRulesFailOnViolationsTest} proves that each rule really fails when broken.
 */
public final class ArchitectureRules {
    private ArchitectureRules() {}

    public static final String ROOT = "com.adrianrevilla.trading";

    /** Infrastructure the domain must never know: frameworks, JDBC, HTTP, metrics, logging, the filesystem of a store. */
    private static final String[] INFRASTRUCTURE = {"org.springframework..", "com.fasterxml.jackson..", "jakarta..",
        "javax.sql..", "java.sql..", "java.net.http..", "com.sun.net.httpserver..", "io.micrometer..",
        "io.opentelemetry..", "org.flywaydb..", "org.postgresql..", "org.slf4j.."};

    /** The application layer may log through the SLF4J facade, nothing else from the list above. */
    private static final String[] INFRASTRUCTURE_FOR_APPLICATION = {"org.springframework..", "com.fasterxml.jackson..",
        "jakarta..", "javax.sql..", "java.sql..", "java.net.http..", "com.sun.net.httpserver..", "io.micrometer..",
        "io.opentelemetry..", "org.flywaydb..", "org.postgresql.."};

    public static ArchRule domainIsFreeOfInfrastructure() {
        return noClasses().that().resideInAPackage("..domain..")
                .should().dependOnClassesThat().resideInAnyPackage(INFRASTRUCTURE)
                .because("the domain changes for business reasons; frameworks and drivers change for technical ones "
                        + "(ADR-0025, skills hexagonal and ddd-tactical)");
    }

    public static ArchRule applicationIsFreeOfInfrastructure() {
        return noClasses().that().resideInAPackage("..application..")
                .should().dependOnClassesThat().resideInAnyPackage(INFRASTRUCTURE_FOR_APPLICATION)
                .because("use cases talk to the outside only through ports; the adapter chooses the technology (ADR-0025)");
    }

    public static ArchRule layersPointInwards() {
        return layeredArchitecture().consideringOnlyDependenciesInLayers()
                .layer("Domain").definedBy("..domain..")
                .layer("Application").definedBy("..application..")
                .layer("Adapters").definedBy("..adapter..")
                .layer("Boot").definedBy(ROOT + ".app..")
                .whereLayer("Boot").mayNotBeAccessedByAnyLayer()
                .whereLayer("Adapters").mayOnlyBeAccessedByLayers("Boot")
                .whereLayer("Application").mayOnlyBeAccessedByLayers("Adapters", "Boot")
                .whereLayer("Domain").mayOnlyBeAccessedByLayers("Application", "Adapters", "Boot")
                .because("dependencies point to the domain: adapter -> application -> domain (ADR-0025)");
    }

    public static ArchRule adaptersDoNotDependOnEachOther() {
        return slices().matching("..adapter.(*).(*)..")
                .should().notDependOnEachOther()
                .because("an adapter is replaceable on its own; adapters meet only through the ports of the application");
    }

    public static ArchRule contextsAreFreeOfCycles() {
        return slices().matching(ROOT + ".(*)..")
                .should().beFreeOfCycles()
                .because("the module graph is a DAG pointing to the domain (skill maven-multimodule-layout)");
    }

    public static ArchRule coreDependsOnNothingOfTheProject() {
        return noClasses().that().resideInAPackage(ROOT + ".core..")
                .should().dependOnClassesThat().resideInAnyPackage(ROOT + ".data..", ROOT + ".backtest..",
                        ROOT + ".strategies..", ROOT + ".risk..", ROOT + ".execution..", ROOT + ".arena..",
                        ROOT + ".tax..", ROOT + ".brokersim..", ROOT + ".application..", ROOT + ".adapter..",
                        ROOT + ".app..")
                .because("the shared kernel depends on nobody (TRD section 3)");
    }

    public static ArchRule strategiesKnowNothingOfBrokersOrExecution() {
        return noClasses().that().resideInAPackage(ROOT + ".strategies..")
                .should().dependOnClassesThat().resideInAnyPackage(ROOT + ".brokersim..", ROOT + ".execution..",
                        ROOT + ".adapter..", ROOT + ".arena..", ROOT + ".application..")
                .because("a strategy does not know in which mode it runs (ADR-0021)");
    }

    public static ArchRule riskIsIndependentOfExecutionAndStrategies() {
        return noClasses().that().resideInAPackage(ROOT + ".risk..")
                .should().dependOnClassesThat().resideInAnyPackage(ROOT + ".execution..", ROOT + ".strategies..",
                        ROOT + ".arena..", ROOT + ".adapter..", ROOT + ".application..")
                .because("risk has the last word over any order and must not depend on who produces it (TRD principle 2)");
    }

    public static ArchRule portsAreInterfaces() {
        return classes().that().resideInAPackage("..port..").and().areTopLevelClasses()
                .should().beInterfaces()
                .because("a port is a contract; its implementation belongs to an adapter or to a use case");
    }

    public static ArchRule noLombok() {
        return noClasses().should().dependOnClassesThat().resideInAPackage("lombok..")
                .because("records and explicit constructors (ADR-0012)");
    }
}
