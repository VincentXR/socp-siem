package com.socp.detect.model.api.controller;

import com.socp.detect.model.engine.AlertWindowAggregator;
import com.socp.detect.model.service.AnalyzeService;
import com.socp.platform.error.api.ApiResult;
import com.socp.platform.error.api.PageResponse;
import com.socp.rule.model.Alert;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/** ModelController 的信封与共享分页契约测试（/analyzed 为 1-based PageResponse）。 */
class ModelControllerTest {

    private final AnalyzeService service = mock(AnalyzeService.class);
    private final AlertWindowAggregator aggregator = mock(AlertWindowAggregator.class);
    private final ModelController controller = new ModelController(service, aggregator);

    @Test
    void analyzedMapsTheZeroBasedServicePageOntoTheSharedOneBasedContract() {
        Alert alert = new Alert("a-1", java.time.Instant.now(), "R-1", "Rule",
                com.socp.rule.model.Severity.HIGH, "msg", "entity", List.of());
        when(service.analyzed(0, 50))
                .thenReturn(new AnalyzeService.AnalyzedPage(List.of(alert), 101, 0, 50, 3));

        ApiResult<PageResponse<Alert>> result = controller.analyzed(1, 50);

        assertThat(result.data().page()).isEqualTo(1);
        assertThat(result.data().size()).isEqualTo(50);
        assertThat(result.data().total()).isEqualTo(101);
        assertThat(result.data().totalPages()).isEqualTo(3);
        assertThat(result.data().items()).containsExactly(alert);
    }

    @Test
    void analyzedClampsPagesBelowOneToOne() {
        when(service.analyzed(0, 50))
                .thenReturn(new AnalyzeService.AnalyzedPage(List.of(), 0, 0, 50, 0));

        assertThat(controller.analyzed(0, 50).data().page()).isEqualTo(1);
        assertThat(controller.analyzed(-5, 50).data().page()).isEqualTo(1);
    }

    @Test
    void singleObjectAndListResponsesAreWrappedInTheApiResultEnvelope() {
        when(service.stats()).thenReturn(Map.of("totalAnalyzed", 3L));
        when(aggregator.snapshot()).thenReturn(Map.of("windowMinutes", 5));
        when(aggregator.trend()).thenReturn(List.of(Map.of("count", 1L)));

        assertThat(controller.stats().data()).containsEntry("totalAnalyzed", 3L);
        assertThat(controller.window().data()).containsEntry("windowMinutes", 5);
        assertThat(controller.windowTrend().data()).hasSize(1);
    }
}
