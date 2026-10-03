package com.socp.gateway.oidc;

import org.junit.jupiter.api.Test;
import java.util.List;
import java.util.Map;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class OidcClaimMappingTest {
    @Test void replacementsAreIndependentOfOverlapAndSourceIterationOrder() {
        for (List<String> groups : List.of(List.of("A", "B"), List.of("B", "A"))) {
            assertThat(OidcClaimMapping.groups(Map.of("groups", groups), "{\"A\":\"B\",\"B\":\"C\"}"))
                    .containsExactlyInAnyOrder("B", "C");
            assertThat(OidcClaimMapping.groups(Map.of("groups", groups), "{\"A\":\"B\",\"B\":\"A\"}"))
                    .containsExactlyInAnyOrder("A", "B");
        }
        assertThat(OidcClaimMapping.permissions(Map.of("permissions", List.of("soar:view", "soar:approve")),
                "{\"soar:view\":\"soar:approve\",\"soar:approve\":\"soar:execute\"}"))
                .containsExactlyInAnyOrder("soar:approve", "soar:execute");
    }
    @Test void unknownPermissionsAndMalformedClaimsCannotBecomeAuthorities() {
        assertThat(OidcClaimMapping.permissions(Map.of("permissions", List.of("SOAR:VIEW", "root:*")), "{}"))
                .containsExactly("soar:view");
        assertThatThrownBy(() -> OidcClaimMapping.groups(Map.of("groups", List.of(42)), "{}"))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> OidcClaimMapping.groups(Map.of("groups", List.of("A")), "not-json"))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
