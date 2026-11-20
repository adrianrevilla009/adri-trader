package com.adrianrevilla.trading.architecture;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.tngtech.archunit.core.domain.JavaClasses;
import com.tngtech.archunit.core.importer.ClassFileImporter;
import com.tngtech.archunit.lang.ArchRule;
import org.junit.jupiter.api.Test;

/**
 * A rule that never fails proves nothing. Each test here feeds a rule a deliberately wrong class from the
 * {@code violations} package (test code, excluded from the real analysis) and demands that the rule fails.
 * If someone weakens a rule so that it lets the violation through, this test fails.
 */
class ArchitectureRulesFailOnViolationsTest {
    private static final String FIXTURES = "com.adrianrevilla.trading.architecture.violations";
    private static final JavaClasses VIOLATIONS = new ClassFileImporter().importPackages(FIXTURES);

    private static void mustFail(ArchRule rule, String mention) {
        assertThatThrownBy(() -> rule.check(VIOLATIONS)).isInstanceOf(AssertionError.class).hasMessageContaining(mention);
    }

    @Test
    void theDomainMayNotUseJdbc() {
        mustFail(ArchitectureRules.domainIsFreeOfInfrastructure(), "DomainUsingJdbc");
    }

    @Test
    void theApplicationMayNotUseHttpDirectly() {
        mustFail(ArchitectureRules.applicationIsFreeOfInfrastructure(), "ApplicationUsingHttp");
    }

    @Test
    void theDomainMayNotDependOnAnAdapter() {
        mustFail(ArchitectureRules.layersPointInwards(), "DomainCallingAdapter");
    }

    @Test
    void adaptersMayNotDependOnEachOther() {
        mustFail(ArchitectureRules.adaptersDoNotDependOnEachOther(), "AdapterOne");
    }

    @Test
    void aPortThatIsNotAnInterfaceIsRejected() {
        mustFail(ArchitectureRules.portsAreInterfaces(), "NotAnInterfacePort");
    }

    @Test
    void theRulesAcceptTheRealCodeBase() {
        // the other side of the coin: the same rules pass on production code (ArchitectureTest runs them all)
        JavaClasses production = new ClassFileImporter()
                .withImportOption(com.tngtech.archunit.core.importer.ImportOption.Predefined.DO_NOT_INCLUDE_TESTS)
                .importPackages(ArchitectureRules.ROOT);
        assertThatCode(() -> ArchitectureRules.domainIsFreeOfInfrastructure().check(production)).doesNotThrowAnyException();
    }
}
