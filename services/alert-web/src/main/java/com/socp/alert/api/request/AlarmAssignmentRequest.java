package com.socp.alert.api.request;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public record AlarmAssignmentRequest(@NotBlank @Size(max = 128) String assignee) {
}
