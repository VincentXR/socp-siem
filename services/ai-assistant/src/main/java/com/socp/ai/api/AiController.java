package com.socp.ai.api;

import com.socp.ai.model.AiResult;
import com.socp.ai.service.AiAssistantService;
import org.springframework.web.bind.annotation.*;

import java.util.Map;

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
    public AiResult ask(@RequestBody Map<String, String> body) {
        return service.ask(body.get("question"));
    }
}
