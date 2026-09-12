package com.socp.threat.web.api.request;

import jakarta.validation.constraints.NotNull;

public record IocLifecycleRequest(@NotNull Boolean revoked) {
}
