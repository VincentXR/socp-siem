package com.socp.soar.web.temporal;

import com.socp.soar.web.service.PlaybookExecutor;
import org.springframework.stereotype.Component;

import java.util.Map;

/**
 * 剧本单动作 Activity 实现——委托给进程内 {@link PlaybookExecutor#executeAction}，
 * 保证 Temporal 模式与进程内模式的动作语义（webhook/notify/case/tag + 重试）完全一致，
 * 不出现行为漂移。
 */
@Component
public class PlaybookActivityImpl implements PlaybookActivity {

    private final PlaybookExecutor executor;

    public PlaybookActivityImpl(PlaybookExecutor executor) {
        this.executor = executor;
    }

    @Override
    public Map<String, Object> executeAction(String action, Map<String, Object> alarm,
                                              boolean activeFailed, int actionIndex) {
        return executor.executeAction(action, alarm, activeFailed, actionIndex);
    }
}
