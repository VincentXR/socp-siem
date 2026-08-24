package com.socp.alert.api;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public record AlarmNoteRequest(
        @Size(max = 128) String author,
        @NotBlank @Size(max = 4096) String content) {
}
