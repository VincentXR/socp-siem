package com.socp.ai.api.controller;

import com.socp.ai.api.request.*;
import com.socp.ai.domain.AiResult;
import com.socp.ai.service.AiAssistantService;
import org.springframework.web.bind.annotation.*;
import jakarta.validation.Valid;

/**
 * AI 助手 API：自然语言安全问答。
 */
@RestController
@RequestMapping("/api/v1/ai")
public class AiController {

    private final AiAssistantService service;

    public AiController(AiAssistantService service) {
        this.service = service;
    }

    @PostMapping("/ask")
    public AiResult ask(@Valid @RequestBody AiAskRequest request) {
        return service.ask(request.question());
    }
}
