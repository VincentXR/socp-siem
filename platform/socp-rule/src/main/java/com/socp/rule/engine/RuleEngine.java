package com.socp.rule.engine;

import com.socp.rule.model.Alert;
import com.socp.rule.model.SecurityEvent;
import com.socp.rule.model.Severity;
import com.socp.rule.rules.Rule;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;

/**
 * 规则引擎：单消费者模型。事件经队列进入，被所有规则评估，
 * 各规则产生的告警统一分发到全部 sink。消费者运行在虚拟线程上。
 * 由 com.siem 迁移（含背压 503 语义：ingest 返回 false 供接入端回 503）。
 */
public final class RuleEngine implements AutoCloseable {

    private static final Logger log = LoggerFactory.getLogger(RuleEngine.class);

    private final AtomicReference<List<Rule>> rulesRef;
    private final List<AlertSink> sinks;
    private final Suppressor suppressor; // 可空
    private final BlockingQueue<SecurityEvent> queue = new ArrayBlockingQueue<>(100_000);
    private volatile boolean running = true;
    private Thread worker;

    private final AtomicLong eventCount = new AtomicLong();
    private final AtomicLong alertCount = new AtomicLong();
    private final AtomicLong dropCount = new AtomicLong();

    // 毒丸事件：用于唤醒消费者线程退出（BlockingQueue 不允许 null）
    private static final SecurityEvent POISON = new SecurityEvent(
            Instant.EPOCH, "POISON", "POISON", "POISON", Map.of(), Severity.INFO);

    public RuleEngine(List<Rule> rules, List<AlertSink> sinks) {
        this(rules, sinks, null);
    }

    public RuleEngine(List<Rule> rules, List<AlertSink> sinks, Suppressor suppressor) {
        this.rulesRef = new AtomicReference<>(List.copyOf(rules));
        this.sinks = List.copyOf(sinks);
        this.suppressor = suppressor;
    }

    public void start() {
        worker = Thread.startVirtualThread(this::loop);
    }

    private void loop() {
        while (running) {
            try {
                SecurityEvent ev = queue.take();
                if (ev == POISON) break; // 毒丸，结束
                eventCount.incrementAndGet();
                List<Rule> rules = rulesRef.get();
                for (Rule r : rules) r.accept(ev);
                for (Rule r : rules) {
                    List<Alert> alerts = r.drain();
                    for (Alert a : alerts) {
                        if (suppressor != null && !suppressor.allow(a)) {
                            continue; // 被抑制
                        }
                        alertCount.incrementAndGet();
                        for (AlertSink s : sinks) s.publish(a);
                    }
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                break;
            }
        }
    }

    /**
     * ingestion 入口：把解析后的事件投入处理队列。
     * 队列满时先施加短暂背压（50ms），给消费者追赶的机会；仍满才丢弃并计数，
     * 避免突发流量下静默丢失过多数据（生产中再升级为持久化/分片）。
     *
     * @return true=已入队；false=队列满被丢弃。调用方（如 HTTP 接入端点）据此
     *         向上游采集器（Vector/Fluent Bit）返回 503，触发其重试而不是静默丢数据。
     */
    public boolean ingest(SecurityEvent ev) {
        if (queue.offer(ev)) return true;
        try {
            if (queue.offer(ev, 50, TimeUnit.MILLISECONDS)) return true;
        } catch (InterruptedException ie) {
            Thread.currentThread().interrupt();
        }
        dropCount.incrementAndGet();
        return false;
    }

    /** 队列水位（0.0~1.0），供采集端做自适应限速 / 运维观测。 */
    public double queueLoad() {
        int cap = queue.size() + queue.remainingCapacity();
        return cap == 0 ? 0.0 : (double) queue.size() / cap;
    }

    public void close() {
        running = false;
        queue.offer(POISON); // 唤醒消费者退出
        if (worker != null) {
            try {
                worker.join();
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }
        rulesRef.get().forEach(Rule::close);
        sinks.forEach(AlertSink::close);
    }

    /**
     * 热更新规则：关闭旧规则、原子替换为新规则集，无需重启进程。
     * 进行中的事件可能由旧或新规则评估（窗口内瞬时不一致，可接受）。
     */
    public void reload(List<Rule> newRules) {
        List<Rule> old = rulesRef.getAndSet(List.copyOf(newRules));
        old.forEach(Rule::close);
        log.info("规则已热更新，当前 {} 条", newRules.size());
    }

    /** 各规则命中统计（2026-08-10）：hits/alerts，用于规则健康度观测。 */
    public List<Map<String, Object>> ruleStats() {
        return rulesRef.get().stream().map(Rule::stats).toList();
    }

    public long eventCount() {
        return eventCount.get();
    }

    public long alertCount() {
        return alertCount.get();
    }

    public long dropCount() {
        return dropCount.get();
    }

    public long suppressedCount() {
        return suppressor == null ? 0 : suppressor.suppressed();
    }
}
