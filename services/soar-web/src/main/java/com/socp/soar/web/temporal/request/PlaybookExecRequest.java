package com.socp.soar.web.temporal.request;
import java.io.Serializable;
import java.util.List;
import java.util.Map;

/**
 * 剧本执行请求（Temporal Workflow 入参，Jackson JSON 序列化）。
 */
public record PlaybookExecRequest(
        String playbookId,
        String playbookName,
        String trigger,
        List<String> actions,
        Map<String, Object> alarm
) implements Serializable {
}
