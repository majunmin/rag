package com.majm.rag.config;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.core.env.Environment;
import org.springframework.test.util.ReflectionTestUtils;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Direct unit test of {@link StartupValidator#validate()}; we exercise the
 * branches by setting @Value-injected fields via reflection and stubbing the
 * Environment with the desired active profiles. This keeps the test fast and
 * decoupled from the Spring context.
 */
class StartupValidatorTest {

    private StartupValidator newValidator(String[] activeProfiles, String apiKey, String dbPassword) {
        Environment env = mock(Environment.class);
        when(env.getActiveProfiles()).thenReturn(activeProfiles);
        StartupValidator v = new StartupValidator(env);
        ReflectionTestUtils.setField(v, "openAiApiKey", apiKey);
        ReflectionTestUtils.setField(v, "dbPassword", dbPassword);
        return v;
    }

    @Test
    void nonProdProfile_skipsAllChecks_evenWithBadValues() {
        StartupValidator v = newValidator(new String[]{"dev"}, "", "rag");

        assertThatCode(v::validate).doesNotThrowAnyException();
    }

    @Test
    void noActiveProfile_treatedAsNonProd() {
        StartupValidator v = newValidator(new String[]{}, "", "rag");

        assertThatCode(v::validate).doesNotThrowAnyException();
    }

    @ParameterizedTest
    @ValueSource(strings = {"", " ", "dummy", "DUMMY", "test", "Test", "changeme", "CHANGEME"})
    void prodProfile_rejectsPlaceholderApiKey(String key) {
        StartupValidator v = newValidator(new String[]{"prod"}, key, "real-secret");

        assertThatThrownBy(v::validate)
            .isInstanceOf(IllegalStateException.class)
            .hasMessageContaining("api-key");
    }

    @Test
    void prodProfile_rejectsDevDefaultDbPassword() {
        StartupValidator v = newValidator(new String[]{"prod"}, "sk-real-key", "rag");

        assertThatThrownBy(v::validate)
            .isInstanceOf(IllegalStateException.class)
            .hasMessageContaining("password");
    }

    @Test
    void prodProfile_passesWithRealCredentials() {
        StartupValidator v = newValidator(new String[]{"prod"}, "sk-real-key", "real-db-secret");

        assertThatCode(v::validate).doesNotThrowAnyException();
    }

    @Test
    void productionAlias_alsoTriggersStrictChecks() {
        // "production" is the alternate spelling supported by PROD_PROFILES.
        StartupValidator v = newValidator(new String[]{"production"}, "", "real-secret");

        assertThatThrownBy(v::validate).isInstanceOf(IllegalStateException.class);
    }

    @Test
    void prodProfileMixedWithOthers_stillEnforcesChecks() {
        StartupValidator v = newValidator(new String[]{"common", "prod"}, "dummy", "real");

        assertThat(assertThatThrownBy(v::validate)
            .isInstanceOf(IllegalStateException.class)).isNotNull();
    }
}
