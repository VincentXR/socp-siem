package com.socp.ai.service;

import java.util.List;
import java.util.Locale;

/** Detects common instruction-like content in untrusted log evidence. */
public final class PromptInjectionGuard {
    private static final List<String> MARKERS = List.of(
            "ignore previous instructions", "system prompt", "developer message",
            "execute command", "call tool", "reveal secret", "disregard all rules");

    private PromptInjectionGuard() { }

    public static boolean looksLikeInstruction(String value) {
        if (value == null || value.isBlank()) return false;
        String normalized = value.toLowerCase(Locale.ROOT);
        return MARKERS.stream().anyMatch(normalized::contains);
    }
}
