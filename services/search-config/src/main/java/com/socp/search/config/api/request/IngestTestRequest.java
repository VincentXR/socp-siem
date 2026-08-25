package com.socp.search.config.api.request;
import jakarta.validation.constraints.Size;

public record IngestTestRequest(@Size(max = 65536) String sample) {
}
