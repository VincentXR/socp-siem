package com.socp.search.config.api;

import com.socp.search.config.domain.LogSource;
import com.socp.search.config.domain.ParseFormat;
import com.socp.search.config.domain.SourceType;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.time.Instant;
import java.util.List;

/** API input for log-source configuration; persistence metadata is never client supplied. */
public record LogSourceRequest(
        @NotBlank @Size(max = 128) String name,
        @NotNull SourceType type,
        @NotNull ParseFormat format,
        @Size(max = 2048) String path,
        @Size(max = 512) String address,
        @Size(max = 512) String topic,
        @Size(max = 2000) String env,
        boolean enabled,
        @Size(max = 32) String readFrom,
        @Size(max = 65536) String multiline,
        @Size(max = 128) String sinkTargetId,
        @Size(max = 100) List<@Size(max = 128) String> parseRuleIds,
        @Size(max = 2000) String description,
        @Size(max = 16) String protocol,
        @Size(max = 64) String charset,
        @Size(max = 128) String timeField,
        @Size(max = 64) String timezone,
        @Size(max = 100) List<@Size(max = 128) String> tags,
        @Min(1) @Max(86400) Integer frequency,
        @Size(max = 128) String categoryId,
        @Size(max = 128) String groupId) {

    public LogSource toNewDomain() {
        return LogSource.createFull(name, type, format, path, address, topic, env, enabled,
                readFrom, multiline, sinkTargetId, parseRuleIds, description,
                protocol, charset, timeField, timezone, tags, frequency, categoryId, groupId);
    }

    public LogSource toDomain(String id, Instant createdAt) {
        return new LogSource(id, name, type, format, path, address, topic, env, enabled,
                readFrom, multiline, sinkTargetId,
                parseRuleIds == null ? List.of() : List.copyOf(parseRuleIds), description,
                protocol, charset, timeField, timezone,
                tags == null ? List.of() : List.copyOf(tags), frequency, categoryId, groupId,
                createdAt);
    }
}
