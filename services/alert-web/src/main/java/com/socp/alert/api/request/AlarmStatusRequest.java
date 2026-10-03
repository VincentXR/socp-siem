package com.socp.alert.api.request;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;

public record AlarmStatusRequest(
        @NotBlank @Pattern(regexp = "OPEN|INVESTIGATING|RESOLVED|CLOSED") String status,
        @jakarta.validation.constraints.Size(max = 4096) String reason,
        @jakarta.validation.constraints.Pattern(regexp = "TRUE_POSITIVE|FALSE_POSITIVE|BENIGN|UNDETERMINED") String classification) {
    public AlarmStatusRequest(String status) { this(status, null, null); }
}
