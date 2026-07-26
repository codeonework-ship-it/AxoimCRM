package com.axiom.dispatch;

import com.axiom.audit.AuditService;
import com.axiom.common.SecretCipher;
import com.axiom.integration.AdapterRegistry;
import com.axiom.tenancy.TenantContext;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;

import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.lang.reflect.RecordComponent;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * FR-INT-007: "never displayed after entry".
 *
 * <p>The assertions are on the TYPES, not on a controller's behaviour. A DTO
 * that has a secret field which the controller happens not to populate is one
 * refactor away from leaking; a DTO with no such field cannot leak at all, and
 * that is what these tests pin.
 */
class CredentialSecrecyTest {

    private static final String[] SECRET_WORDS = {"secret", "token", "password", "cipher", "key", "credentialvalue"};

    @BeforeEach void bind() {
        TenantContext.set(new TenantContext.Principal(UUID.randomUUID(), UUID.randomUUID(), "TENANT_ADMIN",
                "Ops Admin", "ops@example.com"));
    }

    @AfterEach void unbind() {
        TenantContext.clear();
    }

    @Test void theCredentialReadDtoHasNoFieldASecretCouldOccupy() {
        RecordComponent[] components = NamedCredentialService.CredentialRow.class.getRecordComponents();

        List<String> names = List.of(components).stream().map(RecordComponent::getName).toList();
        assertTrue(names.contains("secretMasked"), "the DTO reports that a secret exists");
        for (String name : names) {
            if (name.equals("secretMasked")) continue;
            String normalised = name.toLowerCase(Locale.ROOT);
            for (String word : SECRET_WORDS) {
                assertFalse(normalised.contains(word),
                        "read DTO component '" + name + "' looks like it could carry a secret");
            }
        }
    }

    @Test void theMaskIsAConstantAndNotDerivedFromTheStoredValue() {
        JdbcTemplate jdbc = mock(JdbcTemplate.class);
        when(jdbc.query(anyString(), any(RowMapper.class), any(Object[].class))).thenAnswer(invocation -> {
            RowMapper<?> mapper = invocation.getArgument(1);
            Map<String, Object> values = new LinkedHashMap<>();
            values.put("id", UUID.randomUUID());
            values.put("name", "ops-webhook-secret");
            values.put("credential_type", "WEBHOOK_SIGNING_SECRET");
            values.put("description", "Signing key for the operations webhook");
            values.put("rotated_at", Instant.parse("2026-07-25T09:00:00Z"));
            values.put("last_used_at", null);
            values.put("created_at", Instant.parse("2026-07-20T09:00:00Z"));
            values.put("in_use", true);
            return List.of(mapper.mapRow(FakeRows.row(values), 0));
        });

        NamedCredentialService service = new NamedCredentialService(jdbc,
                new SecretCipher("a-development-only-encryption-key-32b"), mock(AuditService.class));

        List<NamedCredentialService.CredentialRow> rows = service.list();

        assertEquals(1, rows.size());
        assertEquals(NamedCredentialService.MASK, rows.get(0).secretMasked());
        assertEquals("********", rows.get(0).secretMasked(),
                "the mask must not leak the length or any character of the stored value");
        assertEquals("ops-webhook-secret", rows.get(0).name(), "the NAME is not a secret and stays readable");
    }

    @Test void secretResolutionIsUnreachableFromAnyRequestPath() throws Exception {
        Method resolve = NamedCredentialService.class.getDeclaredMethod("resolveSecret", String.class);
        assertFalse(Modifier.isPublic(resolve.getModifiers()),
                "resolveSecret must not be public, or a controller could return plaintext");

        Method resolveTarget = ConnectorService.class.getDeclaredMethod("resolveTarget", UUID.class);
        assertFalse(Modifier.isPublic(resolveTarget.getModifiers()),
                "resolveTarget carries a decrypted credential and must stay package-visible");

        for (Method method : IntegrationController.class.getDeclaredMethods()) {
            assertFalse(method.getName().toLowerCase(Locale.ROOT).contains("secret"),
                    "no controller endpoint may be named after a secret: " + method.getName());
        }
    }

    @Test void connectorConfigurationIsMaskedOnReadAndStrippedOnWrite() {
        Map<String, Object> submitted = new LinkedHashMap<>();
        submitted.put("url", "https://receiver.example.com/hook");
        submitted.put("apiToken", "super-secret-value");
        submitted.put("headers", new LinkedHashMap<>(Map.of("Authorization", "Bearer super-secret-value")));

        Map<String, Object> stored = ConfigSanitiser.forStorage(submitted);
        assertFalse(stored.containsKey("apiToken"), "a secret typed into config must never reach the row");
        assertFalse(((Map<?, ?>) stored.get("headers")).containsKey("Authorization"));
        assertEquals("https://receiver.example.com/hook", stored.get("url"));

        Map<String, Object> shown = ConfigSanitiser.forDisplay(submitted);
        assertEquals(ConfigSanitiser.MASK, shown.get("apiToken"), "legacy rows are masked on the way out too");
        assertFalse(String.valueOf(shown).contains("super-secret-value"));
    }

    @Test void theConnectorReadDtoReportsCredentialStatusButNotTheCredential() {
        JdbcTemplate jdbc = mock(JdbcTemplate.class);
        when(jdbc.query(anyString(), any(RowMapper.class), any(Object[].class))).thenAnswer(invocation -> {
            RowMapper<?> mapper = invocation.getArgument(1);
            Map<String, Object> values = new LinkedHashMap<>();
            values.put("id", UUID.randomUUID());
            values.put("connector_type", "WEBHOOK");
            values.put("vendor", "GENERIC_WEBHOOK");
            values.put("display_name", "Ops webhook");
            values.put("enabled", true);
            values.put("config", "{\"url\":\"https://receiver.example.com/hook\","
                    + "\"apiToken\":\"super-secret-value\"}");
            values.put("credential_ref", "ops-webhook-secret");
            values.put("credential_present", true);
            values.put("subscription_count", 2);
            values.put("created_at", Instant.parse("2026-07-20T09:00:00Z"));
            values.put("updated_at", Instant.parse("2026-07-25T09:00:00Z"));
            return List.of(mapper.mapRow(FakeRows.row(values), 0));
        });

        ConnectorService service = new ConnectorService(jdbc, new ObjectMapper(),
                mock(NamedCredentialService.class), mock(AdapterRegistry.class), mock(AuditService.class));

        ConnectorService.ConnectorRow row = service.list().get(0);

        assertEquals("ops-webhook-secret", row.credentialRef(), "the reference is a NAME, not a value");
        assertEquals("SET", row.credentialStatus());
        assertEquals(ConfigSanitiser.MASK, row.config().get("apiToken"));
        assertFalse(row.toString().contains("super-secret-value"),
                "not even the DTO's own toString may echo a secret-shaped config value");
    }
}
