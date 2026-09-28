package com.socp.search.config.api.controller;

import com.socp.platform.error.exception.ApiException;
import com.socp.search.config.service.IngestParseFailureService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.verify;

@ExtendWith(MockitoExtension.class)
class IngestParseFailureControllerTest {
    @Mock IngestParseFailureService failures;

    @Test
    void listPreservesRequestedPageAndTenantScopedServiceResult() {
        var item = Map.<String, Object>of("id", "failure-1", "replayStatus", "PENDING");
        given(failures.page(2, 25)).willReturn(
                new PageImpl<>(List.of(item), PageRequest.of(1, 25), 50));

        var result = new IngestParseFailureController(failures).list(2, 25).data();

        assertThat(result.items()).containsExactly(item);
        assertThat(result.total()).isEqualTo(50);
        assertThat(result.page()).isEqualTo(2);
        assertThat(result.size()).isEqualTo(25);
        verify(failures).page(2, 25);
    }

    @Test
    void listRejectsInvalidBoundsBeforeQueryingStorage() {
        var controller = new IngestParseFailureController(failures);

        assertThatThrownBy(() -> controller.list(0, 20))
                .isInstanceOf(ApiException.class)
                .hasFieldOrPropertyWithValue("code", 400);
        assertThatThrownBy(() -> controller.list(1, 101))
                .isInstanceOf(ApiException.class)
                .hasFieldOrPropertyWithValue("code", 400);
    }

    @Test
    void replayReturnsTheExactQuarantineRowOutcome() {
        var replay = Map.<String, Object>of(
                "id", "failure-1", "replayStatus", "REPLAYED", "created", 1);
        given(failures.replay("failure-1")).willReturn(replay);

        assertThat(new IngestParseFailureController(failures).replay("failure-1").data())
                .isSameAs(replay);
        verify(failures).replay("failure-1");
    }
}
