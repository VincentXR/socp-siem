package com.socp.detect.web.engine;

import com.socp.rule.engine.AlertSink;
import com.socp.rule.model.Alert;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * 告警出口（进程内实现）：保留最近 N 条告警供 API 查询/前端看板。
 * 生产环境替换为 Kafka sink（socp-detect-original-alarm 主题）→ GASModel 窗口聚合 → ALERT 落 PG。
 */
@Component
public class RecentAlertSink implements AlertSink {

    private final List<Alert> recent = new CopyOnWriteArrayList<>();
    private final int capacity;
    private final AlertForwarder forwarder;
    private final AlertStreamHub streamHub;

    @org.springframework.beans.factory.annotation.Autowired
    public RecentAlertSink(AlertForwarder forwarder, AlertStreamHub streamHub) {
        this.capacity = 500;
        this.forwarder = forwarder;
        this.streamHub = streamHub;
    }

    public RecentAlertSink(int capacity, AlertForwarder forwarder, AlertStreamHub streamHub) {
        this.capacity = capacity;
        this.forwarder = forwarder;
        this.streamHub = streamHub;
    }

    @Override
    public void publish(Alert alert) {
        recent.add(alert);
        int over = recent.size() - capacity;
        for (int i = 0; i < over && !recent.isEmpty(); i++) {
            recent.remove(0);
        }
        // 实时推送给 SSE 订阅者（前端大屏即时刷新）
        if (streamHub != null) streamHub.broadcast(alert);
        // best-effort 转发到 ALERT（异步虚拟线程，不阻塞检测热路径）
        if (forwarder != null) forwarder.forward(alert);
    }

    public List<Alert> recent() {
        return List.copyOf(recent);
    }

    @Override
    public void close() {
        // 共享 sink，引擎重建时不做清理
    }
}
