package com.socp.platform.tenant.context;

import org.springframework.core.task.TaskDecorator;
import org.springframework.stereotype.Component;

/** Spring executor decorator that propagates and reliably clears tenant context. */
@Component
public class TenantTaskDecorator implements TaskDecorator {

    @Override
    public Runnable decorate(Runnable runnable) {
        String tenant = TenantContext.get();
        if (tenant == null) {
            return () -> {
                TenantContext.clear();
                try {
                    runnable.run();
                } finally {
                    TenantContext.clear();
                }
            };
        }
        return TenantContext.wrap(tenant, runnable);
    }
}
