package com.socp.soar.web.service;

import com.socp.platform.tenant.context.TenantContext;
import com.socp.platform.tenant.persistence.TenantSystemJob;
import com.socp.soar.web.config.SoarRuntimeProperties;
import com.socp.soar.web.domain.Playbook;
import com.socp.soar.web.persistence.store.PlaybookStore;
import com.socp.soar.web.persistence.store.ScheduledPlaybookRunStore;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.DateTimeException;
import java.time.Instant;
import java.time.LocalTime;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.time.temporal.ChronoUnit;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Pattern;

/**
 * Tenant-aware fallback scheduler for enabled time-triggered playbooks.
 * A durable claim prevents multiple SOAR instances from firing the same
 * tenant/playbook/minute more than once.
 */
@Component
@EnableScheduling
public class ScheduledPlaybookRunner {

    private static final Logger log = LoggerFactory.getLogger(ScheduledPlaybookRunner.class);
    private static final Pattern CLOCK_TIME = Pattern.compile("(\\d{1,2})[:：](\\d{2})");
    private static final Pattern HOUR_ONLY = Pattern.compile("(\\d{1,2})[点时時]");

    private final PlaybookStore store;
    private final PlaybookExecutor executor;
    private final ScheduledPlaybookRunStore runStore;
    private final ZoneId scheduleZone;

    public ScheduledPlaybookRunner(PlaybookStore store, PlaybookExecutor executor,
                                   ScheduledPlaybookRunStore runStore,
                                   SoarRuntimeProperties properties) {
        this.store = store;
        this.executor = executor;
        this.runStore = runStore;
        try {
            this.scheduleZone = ZoneId.of(properties.getScheduleZone());
        } catch (DateTimeException invalidZone) {
            throw new IllegalArgumentException(
                    "invalid socp.soar.schedule-zone: " + properties.getScheduleZone(), invalidZone);
        }
    }

    @Scheduled(fixedDelayString = "${socp.soar.schedule-poll-ms:60000}",
            initialDelayString = "${socp.soar.schedule-initial-delay-ms:10000}")
    @TenantSystemJob
    public void tick() {
        tick(ZonedDateTime.now(scheduleZone));
    }

    void tick(ZonedDateTime now) {
        for (String tenant : store.tenantsWithEnabledPlaybooks()) {
            try {
                TenantContext.runWith(tenant, () -> tickTenant(now));
            } catch (RuntimeException tenantFailure) {
                log.error("Scheduled playbook scan failed tenant={}: {}", tenant,
                        tenantFailure.getMessage(), tenantFailure);
            }
        }
    }

    private void tickTenant(ZonedDateTime now) {
        LocalTime currentMinute = now.toLocalTime().truncatedTo(ChronoUnit.MINUTES);
        Instant scheduledFor = now.truncatedTo(ChronoUnit.MINUTES).toInstant();
        String tenant = TenantContext.require();
        for (Playbook playbook : store.list()) {
            if (!playbook.enabled() || !isScheduled(playbook.trigger())) continue;
            LocalTime scheduledTime = parseTime(playbook.trigger());
            if (scheduledTime == null || !currentMinute.equals(scheduledTime)) continue;

            ScheduledPlaybookRunStore.Claim claim = runStore.claim(
                    tenant, playbook.id(), scheduledFor);
            if (claim == null) {
                log.debug("Scheduled playbook already claimed tenant={} playbook={} fire={}",
                        tenant, playbook.id(), scheduledFor);
                continue;
            }
            executeClaimed(playbook, now, claim);
        }
    }

    private void executeClaimed(Playbook playbook, ZonedDateTime now,
                                ScheduledPlaybookRunStore.Claim claim) {
        log.info("Scheduled playbook triggered tenant={} playbook={} fire={}",
                claim.tenant(), playbook.id(), claim.scheduledFor());
        Map<String, Object> context = new LinkedHashMap<>();
        context.put("tenantId", claim.tenant());
        context.put("ruleId", "SCHEDULE-" + shortId(playbook.id()));
        context.put("severity", "INFO");
        // Stable across node races and retries, so downstream action keys remain stable.
        context.put("id", "sched-" + claim.id());
        context.put("scheduledAt", now.format(DateTimeFormatter.ISO_OFFSET_DATE_TIME));
        try {
            executor.runById(playbook.id(), context);
            runStore.complete(claim);
        } catch (RuntimeException executionFailure) {
            runStore.fail(claim, executionFailure);
            log.error("Scheduled playbook execution failed tenant={} playbook={}: {}",
                    claim.tenant(), playbook.id(), executionFailure.getMessage(), executionFailure);
        }
    }

    static LocalTime parseTime(String trigger) {
        if (trigger == null) return null;
        String compact = trigger.replaceAll("\\s+", "");
        var match = CLOCK_TIME.matcher(compact);
        if (match.find()) {
            return validTime(match.group(1), match.group(2));
        }
        match = HOUR_ONLY.matcher(compact);
        if (match.find()) {
            return validTime(match.group(1), "0");
        }
        return null;
    }

    /** Retained for callers that only need the hour component. */
    static Integer parseHour(String trigger) {
        LocalTime time = parseTime(trigger);
        return time == null ? null : time.getHour();
    }

    private static LocalTime validTime(String hourText, String minuteText) {
        try {
            int hour = Integer.parseInt(hourText);
            int minute = Integer.parseInt(minuteText);
            return LocalTime.of(hour, minute);
        } catch (DateTimeException | NumberFormatException invalidTime) {
            return null;
        }
    }

    private static boolean isScheduled(String trigger) {
        if (trigger == null) return false;
        String normalized = trigger.toLowerCase(Locale.ROOT);
        return trigger.contains("定时") || normalized.contains("schedule");
    }

    private static String shortId(String id) {
        if (id == null || id.isBlank()) return "unknown";
        return id.substring(0, Math.min(6, id.length()));
    }
}
