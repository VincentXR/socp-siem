package com.socp.soar.web.service;

import com.socp.soar.web.domain.Playbook;
import com.socp.soar.web.persistence.store.PlaybookStore;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 定时剧本调度器：模拟 Temporal Schedule —— 每天整点检查启用中的"定时"剧本，
 * 命中触发表达式（如"每天 03:00"）则执行一次。
 *
 * <p>生产环境由 Temporal Schedule / Cron 承担；此处用 @Scheduled 每分钟探一次，
 * 语义等价（cron 匹配在进程内完成）。
 */
@Component
@EnableScheduling
public class ScheduledPlaybookRunner {

    private static final Logger log = LoggerFactory.getLogger(ScheduledPlaybookRunner.class);

    private final PlaybookStore store;
    private final PlaybookExecutor executor;

    public ScheduledPlaybookRunner(PlaybookStore store, PlaybookExecutor executor) {
        this.store = store;
        this.executor = executor;
    }

    /** 每分钟检查一次是否到点。 */
    @Scheduled(fixedDelay = 60_000, initialDelay = 10_000)
    public void tick() {
        ZonedDateTime now = ZonedDateTime.now();
        for (Playbook pb : store.list()) {
            if (!pb.enabled()) continue;
            String trigger = pb.trigger();
            if (trigger == null || !(trigger.contains("定时") || trigger.toLowerCase().contains("schedule"))) {
                continue;
            }
            Integer hour = parseHour(trigger);
            if (hour != null && now.getHour() == hour && now.getMinute() == 0) {
                log.info("定时剧本触发: {} ({}:00)", pb.name(), hour);
                Map<String, Object> ctx = new LinkedHashMap<>();
                ctx.put("ruleId", "SCHEDULE-" + pb.id().substring(0, 6));
                ctx.put("severity", "INFO");
                ctx.put("id", "sched-" + java.util.UUID.randomUUID().toString().substring(0, 8));
                ctx.put("scheduledAt", now.format(DateTimeFormatter.ISO_LOCAL_DATE_TIME));
                executor.runById(pb.id(), ctx);
            }
        }
    }

    /** 解析"每天 HH:MM"或"每天 HH 点"中的小时，解析失败返回 null。 */
    static Integer parseHour(String trigger) {
        String t = trigger.replaceAll("\\s+", "");
        // 匹配 每天03:00 / 每天 03:00 / 03:00
        var m = java.util.regex.Pattern.compile("(\\d{1,2})[:：](\\d{2})").matcher(t);
        if (m.find()) {
            return Integer.parseInt(m.group(1));
        }
        m = java.util.regex.Pattern.compile("(\\d{1,2})[点时]").matcher(t);
        if (m.find()) {
            int h = Integer.parseInt(m.group(1));
            return (h >= 0 && h <= 23) ? h : null;
        }
        return null;
    }
}
