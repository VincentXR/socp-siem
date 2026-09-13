package com.socp.ai.api.controller;

import com.socp.ai.api.request.AiAskRequest;
import com.socp.ai.api.request.AppendInvestigationRequest;
import com.socp.ai.api.request.InvestigationRequest;
import com.socp.ai.domain.AiResponseSource;
import com.socp.ai.domain.AiResult;
import com.socp.ai.service.AiAssistantService;
import com.socp.ai.service.AsyncInvestigationJobService;
import com.socp.ai.service.InvestigationAgentService;
import com.socp.platform.error.api.ApiResult;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class AiControllerTest {

    @Test
    void exposesAllInvestigationOperationsThroughApiResult() {
        AiAssistantService assistant = mock(AiAssistantService.class);
        InvestigationAgentService investigation = mock(InvestigationAgentService.class);
        AiController controller = new AiController(assistant, investigation);
        AiResult answer = new AiResult("question", "answer", null, 1L, AiResponseSource.FALLBACK);
        Map<String, Object> result = Map.of("status", "COMPLETED");

        when(assistant.ask("question")).thenReturn(answer);
        when(investigation.investigate("alert-1")).thenReturn(result);
        when(investigation.get("investigation-1")).thenReturn(result);
        when(investigation.appendToIncident("investigation-1", "case-1")).thenReturn(result);

        assertThat(controller.ask(new AiAskRequest("question")).data()).isEqualTo(answer);
        assertThat(controller.investigate(new InvestigationRequest("alert-1")).data()).isEqualTo(result);
        assertThat(controller.getInvestigation("investigation-1").data()).isEqualTo(result);
        assertThat(controller.appendToIncident("investigation-1",
                new AppendInvestigationRequest("case-1")).data()).isEqualTo(result);

        AsyncInvestigationJobService async = mock(AsyncInvestigationJobService.class);
        when(async.submit("alert-1")).thenReturn(Map.of("status", "ACCEPTED"));
        ReflectionTestUtils.setField(controller, "asyncInvestigation", async);
        ApiResult<Map<String, Object>> queued = controller.investigateAsync(new InvestigationRequest("alert-1"));
        assertThat(queued.data()).containsEntry("status", "ACCEPTED");
    }
}
