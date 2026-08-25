package com.socp.search.config.api.controller;





import com.socp.search.config.persistence.store.*;
import com.socp.search.config.parser.*;
import com.socp.search.config.domain.*;
import com.socp.search.config.domain.*;
import com.socp.search.config.infrastructure.kafka.*;
import com.socp.search.config.infrastructure.opensearch.*;
import com.socp.search.config.infrastructure.serialization.*;
import com.socp.search.config.persistence.entity.*;
import com.socp.search.config.persistence.repository.*;
import com.socp.search.config.persistence.store.*;
import com.socp.search.config.service.*;
import com.socp.search.config.api.request.*;
import com.socp.platform.error.api.ApiResult;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

/** Runtime health endpoint for source configuration and event ingest. */
@RestController
public class HealthController {
    @GetMapping("/health")
    public ApiResult<Map<String, Object>> health() {
        return ApiResult.ok(Map.of("service", "search-config", "status", "UP"));
    }
}
