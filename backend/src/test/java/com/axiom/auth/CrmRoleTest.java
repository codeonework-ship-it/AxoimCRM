package com.axiom.auth;

import com.axiom.common.ForbiddenException;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class CrmRoleTest {
    @Test void superAuditIsPlatformWideAndReadOnly() {
        assertTrue(CrmRole.SUPER_AUDIT.platform());
        assertTrue(CrmRole.SUPER_AUDIT.readOnly());
        assertFalse(CrmRole.SUPER_AUDIT.importAllowed());
    }

    @Test void superAdminCanAdministerAndImportMasters() {
        assertTrue(CrmRole.SUPER_ADMIN.platform());
        assertTrue(CrmRole.SUPER_ADMIN.masterAdmin());
        assertTrue(CrmRole.SUPER_ADMIN.importAllowed());
    }

    @Test void unknownRoleFailsClosed() {
        assertThrows(ForbiddenException.class, () -> CrmRole.current("ROOT"));
    }
}
