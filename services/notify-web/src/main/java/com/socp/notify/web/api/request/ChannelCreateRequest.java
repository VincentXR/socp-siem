package com.socp.notify.web.api.request;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

public record ChannelCreateRequest(
        @NotBlank @Size(max = 128) String name,
        @NotBlank @Pattern(regexp = "WEBHOOK|SLACK|DINGTALK|WECOM|WECHAT|EMAIL|LOG") String type,
        @NotBlank @Size(max = 2048) String target,
        Boolean enabled,
        @Size(max = 512) String description) {

    public boolean enabledOrDefault() {
        return enabled == null || enabled;
    }
}
