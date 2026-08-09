package com.socp.report.web.api;

import com.socp.report.web.model.ReportSummary;
import com.socp.report.web.service.ReportService;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;

/**
 * REPORT 报表 API：日报 + 7 日趋势。
 */
@RestController
@RequestMapping("/api/v1/reports")
public class ReportController {

    private final ReportService service;

    public ReportController(ReportService service) {
        this.service = service;
    }

    @GetMapping("/daily")
    public ReportSummary daily() {
        return service.dailyReport();
    }

    @GetMapping("/trend7d")
    public Map<String, Object> trend7d() {
        return service.trend7d();
    }
}
