package com.adrianrevilla.trading.architecture;

import com.tngtech.archunit.core.importer.ImportOption;
import com.tngtech.archunit.junit.AnalyzeClasses;
import com.tngtech.archunit.junit.ArchTest;
import com.tngtech.archunit.lang.ArchRule;

/**
 * Runs the architecture rules over the production classes of every module (this module depends on all of them, so
 * the whole system is on the classpath). A violation fails {@code mvn verify}. See {@link ArchitectureRules}.
 */
@AnalyzeClasses(packages = ArchitectureRules.ROOT, importOptions = ImportOption.DoNotIncludeTests.class)
class ArchitectureTest {

    @ArchTest
    static final ArchRule domain_is_free_of_infrastructure = ArchitectureRules.domainIsFreeOfInfrastructure();

    @ArchTest
    static final ArchRule application_is_free_of_infrastructure = ArchitectureRules.applicationIsFreeOfInfrastructure();

    @ArchTest
    static final ArchRule layers_point_inwards = ArchitectureRules.layersPointInwards();

    @ArchTest
    static final ArchRule adapters_do_not_depend_on_each_other = ArchitectureRules.adaptersDoNotDependOnEachOther();

    @ArchTest
    static final ArchRule contexts_are_free_of_cycles = ArchitectureRules.contextsAreFreeOfCycles();

    @ArchTest
    static final ArchRule core_depends_on_nothing_of_the_project = ArchitectureRules.coreDependsOnNothingOfTheProject();

    @ArchTest
    static final ArchRule strategies_know_nothing_of_brokers = ArchitectureRules.strategiesKnowNothingOfBrokersOrExecution();

    @ArchTest
    static final ArchRule risk_is_independent = ArchitectureRules.riskIsIndependentOfExecutionAndStrategies();

    @ArchTest
    static final ArchRule ports_are_interfaces = ArchitectureRules.portsAreInterfaces();

    @ArchTest
    static final ArchRule no_lombok = ArchitectureRules.noLombok();
}
