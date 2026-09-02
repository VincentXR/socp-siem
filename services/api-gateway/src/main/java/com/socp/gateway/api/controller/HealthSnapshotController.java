package com.socp.gateway.api.controller;

import com.socp.gateway.api.health.HealthSnapshot;
import com.socp.gateway.api.health.HealthSnapshotService;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import reactor.core.publisher.Mono;

/** Authenticated, cached health summary for the workbench overview. */
@RestController
@RequestMapping("/api/v1/system")
public class HealthSnapshotController {

    private final HealthSnapshotService healthSnapshotService;

    public HealthSnapshotController(HealthSnapshotService healthSnapshotService) {
        this.healthSnapshotService = healthSnapshotService;
    }

    @GetMapping("/health")
    public Mono<HealthSnapshot> health() {
        return healthSnapshotService.snapshot();
    }
}
