package com.socp.soar.web.service;

import com.socp.soar.web.domain.Playbook;
import com.socp.soar.web.domain.PlaybookActionType;

import java.util.EnumSet;

/**
 * Single source of truth for actions which can change a production environment.
 * Keeping this policy independent of persistence lets every execution entry point
 * (manual, scheduled, service-to-service and Temporal) apply the same rule.
 */
public final class ApprovalPolicy {
    private static final EnumSet<PlaybookActionType> HIGH_RISK = EnumSet.of(
            PlaybookActionType.NETWORK_ISOLATE, PlaybookActionType.FIREWALL_BLOCK);

    private ApprovalPolicy() { }

    public static boolean requiresApproval(Playbook playbook) {
        return playbook != null && playbook.actions().stream()
                .map(PlaybookActionType::resolve)
                .anyMatch(HIGH_RISK::contains);
    }
}
