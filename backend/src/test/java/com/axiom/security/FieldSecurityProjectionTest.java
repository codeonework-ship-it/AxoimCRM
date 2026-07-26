package com.axiom.security;

import com.axiom.tenancy.TenantContext;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * FR-SEC-007's on-failure clause, which is the whole requirement in one line:
 * "a hidden field must be absent from responses entirely, not returned as null —
 * absence and emptiness must not be conflated."
 *
 * <p>The distinction is not pedantry. For {@code annualRevenue}, {@code null}
 * means "we do not know this company's revenue" and absence means "you are not
 * cleared to see it". A rep sizing an account acts differently on those two, and
 * a downstream report that receives null cannot tell them apart at all.
 */
class FieldSecurityProjectionTest {

    private static final UUID TENANT = UUID.fromString("11111111-1111-1111-1111-111111111111");
    private static final UUID USER = UUID.fromString("22222222-2222-2222-2222-222222222222");

    private PermissionResolver resolver;
    private SensitiveFieldService sensitiveFields;
    private FieldSecurityService fieldSecurity;

    /** A DTO with one hidden field, one genuinely-null field and one visible field. */
    record AccountDto(UUID id, String name, String industry, String taxId) {}

    @BeforeEach void setUp() {
        resolver = mock(PermissionResolver.class);
        sensitiveFields = mock(SensitiveFieldService.class);
        fieldSecurity = new FieldSecurityService(resolver, sensitiveFields, new ObjectMapper());
        TenantContext.set(new TenantContext.Principal(TENANT, USER, "SALES",
                "Priya Nair", "priya.nair@meridianfab.com"));
        when(sensitiveFields.registry(any(), any())).thenReturn(Map.of());
    }

    @AfterEach void tearDown() {
        TenantContext.clear();
    }

    private void unreadable(Set<String> hidden) {
        when(resolver.current()).thenReturn(context(hidden, Set.of()));
    }

    private AccessContext context(Set<String> hidden, Set<String> readOnly) {
        return new AccessContext(TENANT, USER, "SALES", false, false,
                UUID.randomUUID(), "SALES", 2000, List.of(), Set.of("ACCOUNT.READ"),
                Map.of(SecurableObject.ACCOUNT, AccessContext.ObjectAccess.READ_ALL),
                Map.of(SecurableObject.ACCOUNT, hidden),
                Map.of(SecurableObject.ACCOUNT, readOnly),
                null, null, null, List.of(), List.of());
    }

    @Test void aHiddenFieldIsAbsentFromTheProjectionRatherThanNull() {
        unreadable(Set.of("industry"));
        AccountDto dto = new AccountDto(UUID.randomUUID(), "Meridian Fabrication", "Manufacturing", null);

        Map<String, Object> projected = fieldSecurity.project(SecurableObject.ACCOUNT, dto);

        assertFalse(projected.containsKey("industry"),
                "a hidden field must be ABSENT — containsKey must be false, not value null");
        assertNull(projected.get("industry"), "get() on an absent key is null, which is why "
                + "containsKey is the assertion that matters");
    }

    @Test void aNullButReadableFieldIsPresentWithANullValue() {
        unreadable(Set.of("industry"));
        AccountDto dto = new AccountDto(UUID.randomUUID(), "Meridian Fabrication", "Manufacturing", null);

        Map<String, Object> projected = fieldSecurity.project(SecurableObject.ACCOUNT, dto);

        assertTrue(projected.containsKey("taxId"),
                "a field the user MAY read stays present even when its value is null — "
                        + "that is the emptiness that must not be confused with absence");
        assertNull(projected.get("taxId"));
    }

    @Test void theIdentifierIsAlwaysReadableEvenIfDenied() {
        unreadable(Set.of("id", "industry"));
        AccountDto dto = new AccountDto(UUID.randomUUID(), "Meridian", "Manufacturing", "GSTIN123");

        Map<String, Object> projected = fieldSecurity.project(SecurableObject.ACCOUNT, dto);

        assertTrue(projected.containsKey("id"), "an unaddressable record is not a security win");
        assertFalse(projected.containsKey("industry"));
    }

    @Test void nothingIsRemovedWhenNothingIsDenied() {
        unreadable(Set.of());
        AccountDto dto = new AccountDto(UUID.randomUUID(), "Meridian", "Manufacturing", "GSTIN123");

        Map<String, Object> projected = fieldSecurity.project(SecurableObject.ACCOUNT, dto);

        assertEquals(Set.of("id", "name", "industry", "taxId"), projected.keySet());
    }

    @Test void everyRowOfAListIsProjected() {
        unreadable(Set.of("industry"));
        List<AccountDto> rows = List.of(
                new AccountDto(UUID.randomUUID(), "One", "Manufacturing", null),
                new AccountDto(UUID.randomUUID(), "Two", "Mining", null));

        List<Map<String, Object>> projected = fieldSecurity.projectAll(SecurableObject.ACCOUNT, rows);

        assertEquals(2, projected.size());
        assertTrue(projected.stream().noneMatch(row -> row.containsKey("industry")),
                "one unprojected row in a list is the whole leak");
    }

    @Test void writingAFieldTheProfileCannotReadIsRefusedNamingTheField() {
        when(resolver.current()).thenReturn(context(Set.of("industry"), Set.of()));

        com.axiom.common.ForbiddenException refusal = assertThrows(
                com.axiom.common.ForbiddenException.class,
                () -> fieldSecurity.requireEditable(SecurableObject.ACCOUNT, List.of("industry", "name")));

        assertTrue(refusal.getMessage().contains("industry"), refusal.getMessage());
        assertFalse(refusal.getMessage().contains("name"),
                "only the refused field should be named, or the message is noise");
    }

    @Test void aReadOnlyFieldCannotBeWrittenEitherEvenThoughItIsVisible() {
        when(resolver.current()).thenReturn(context(Set.of(), Set.of("taxId")));

        assertThrows(com.axiom.common.ForbiddenException.class,
                () -> fieldSecurity.requireEditable(SecurableObject.ACCOUNT, List.of("taxId")));
    }
}
