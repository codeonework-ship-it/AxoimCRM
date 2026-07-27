package com.axiom.identity;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ScimGroupServiceTest {
    @Test void acceptsTheDirectoryGroupLookupFilter() {
        assertThat(ScimGroupService.parseFilter("displayName eq \"Revenue Operators\""))
                .isEqualTo("Revenue Operators");
    }

    @Test void rejectsFiltersThatWouldOtherwiseSilentlyReturnEveryGroup() {
        assertThatThrownBy(() -> ScimGroupService.parseFilter("members.value co abc"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("displayName eq");
    }
}
