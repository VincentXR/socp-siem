package com.socp.alert.api.controller;

import com.socp.alert.api.request.TechniqueCountsRequest;
import com.socp.alert.service.AlarmStatisticsService;
import com.socp.platform.auth.security.RequestBodyLimit;
import com.socp.platform.auth.security.RequireRole;
import com.socp.platform.error.api.ApiResult;
import com.socp.platform.ratelimit.api.RateLimit;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** Tenant-owned alert activity for the ATT&CK view, independent of overview samples. */
@RestController
@RequestMapping({"/api/v1/alarms", "/api/alarms"})
public class AlarmTechniqueController {
    private final AlarmStatisticsService statistics;

    public AlarmTechniqueController(AlarmStatisticsService statistics) { this.statistics = statistics; }

    @PostMapping("/technique-counts")
    @RequireRole({"admin", "analyst", "viewer"})
    @RequestBodyLimit(maxBytes = 16384)
    @RateLimit(permits = 5)
    public ApiResult<AlarmStatisticsService.TechniqueCounts> counts(@Valid @RequestBody TechniqueCountsRequest request) {
        return ApiResult.ok(statistics.techniqueCounts(request.techniqueIds()));
    }
}
