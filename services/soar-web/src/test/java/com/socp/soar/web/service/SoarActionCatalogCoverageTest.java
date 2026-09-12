package com.socp.soar.web.service;

import com.socp.soar.web.domain.Playbook;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class SoarActionCatalogCoverageTest {

    @Test
    void toLegacyActionMapsKnownRefsAndStripsVersionSuffix() {
        assertThat(SoarActionCatalog.toLegacyAction(null)).isEmpty();
        assertThat(SoarActionCatalog.toLegacyAction("   ")).isEmpty();
        assertThat(SoarActionCatalog.toLegacyAction(" socp.notify/send ")).isEqualTo("notify");
        assertThat(SoarActionCatalog.toLegacyAction("SOCP.INCIDENT/CREATE")).isEqualTo("case");
        assertThat(SoarActionCatalog.toLegacyAction("net.firewall/block")).isEqualTo("firewall-block");
        assertThat(SoarActionCatalog.toLegacyAction("endpoint/isolate-host@2")).isEqualTo("network-isolate");
        assertThat(SoarActionCatalog.toLegacyAction("endpoint/snapshot-host@3")).isEqualTo("snapshot");
        assertThat(SoarActionCatalog.toLegacyAction("http/webhook")).isEqualTo("webhook");
        assertThat(SoarActionCatalog.toLegacyAction("custom.family/thing")).isEqualTo("custom.family/thing");
    }

    @Test
    void isKnownAcceptsBuiltinAndLegacyRefsWithOptionalVersion() {
        assertThat(SoarActionCatalog.isKnown(null)).isFalse();
        assertThat(SoarActionCatalog.isKnown("   ")).isFalse();
        assertThat(SoarActionCatalog.isKnown("socp.alert/get")).isTrue();
        assertThat(SoarActionCatalog.isKnown("socp.alert/get@3")).isTrue();
        assertThat(SoarActionCatalog.isKnown("SOCP.SEARCH/SEARCH-EVENTS")).isTrue();
        assertThat(SoarActionCatalog.isKnown("socp.notify/send")).isTrue();
        assertThat(SoarActionCatalog.isKnown("http.webhook/request")).isTrue();
        assertThat(SoarActionCatalog.isKnown("firewall/unblock-ioc")).isTrue();
        assertThat(SoarActionCatalog.isKnown("unknown/action")).isFalse();
        assertThat(SoarActionCatalog.isKnown("custom.family/thing")).isFalse();
    }

    @Test
    void isNamespacedValidatesShapeAndVersionSuffix() {
        assertThat(SoarActionCatalog.isNamespaced(null)).isFalse();
        assertThat(SoarActionCatalog.isNamespaced("   ")).isFalse();
        assertThat(SoarActionCatalog.isNamespaced("ab/cd")).isTrue();
        assertThat(SoarActionCatalog.isNamespaced("socp.alert/get")).isTrue();
        assertThat(SoarActionCatalog.isNamespaced("socp.alert/get@2")).isTrue();
        assertThat(SoarActionCatalog.isNamespaced("socp.alert/get@10")).isTrue();
        assertThat(SoarActionCatalog.isNamespaced("socp.alert/get@01")).isTrue();
        assertThat(SoarActionCatalog.isNamespaced("socp.alert/get@X")).isFalse();
        assertThat(SoarActionCatalog.isNamespaced("NoUpper/case")).isFalse();
        assertThat(SoarActionCatalog.isNamespaced("noslash")).isFalse();
        assertThat(SoarActionCatalog.isNamespaced("1bad/name")).isFalse();
    }

    @Test
    void approvalPolicyRequiresApprovalOnlyForHighRiskActions() {
        assertThat(ApprovalPolicy.requiresApproval(null)).isFalse();
        Playbook notifyOnly = Playbook.create("notify", "manual", List.of("notify"), true);
        assertThat(ApprovalPolicy.requiresApproval(notifyOnly)).isFalse();
        Playbook firewall = Playbook.create("firewall", "manual", List.of("firewall-block"), true);
        assertThat(ApprovalPolicy.requiresApproval(firewall)).isTrue();
        Playbook isolate = Playbook.create("isolate", "manual", List.of("endpoint/isolate-host"), true);
        assertThat(ApprovalPolicy.requiresApproval(isolate)).isTrue();
    }
}
