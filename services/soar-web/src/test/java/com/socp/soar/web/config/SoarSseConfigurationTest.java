package com.socp.soar.web.config;

import org.junit.jupiter.api.Test;

import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

class SoarSseConfigurationTest {

    @Test
    void schedulerHonorsBoundsAndShutsDownCleanly() throws Exception {
        SoarRuntimeProperties properties = new SoarRuntimeProperties();
        properties.setSseSchedulerThreads(0);
        SoarSseConfiguration configuration = new SoarSseConfiguration();
        ScheduledExecutorService scheduler = configuration.soarSseScheduler(properties);
        try {
            assertThat(scheduler.isShutdown()).isFalse();
            scheduler.submit(() -> { }).get(2, TimeUnit.SECONDS);
        } finally {
            scheduler.shutdown();
            assertThat(scheduler.awaitTermination(2, TimeUnit.SECONDS)).isTrue();
        }
    }
}
