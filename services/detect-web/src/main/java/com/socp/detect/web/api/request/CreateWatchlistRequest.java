package com.socp.detect.web.api.request;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

import java.util.List;

public record CreateWatchlistRequest(
        @NotBlank @Size(max = 255)
        @Pattern(regexp = "(?!\\.{1,2}$)[\\p{L}\\p{N}_.-]+",
                message = "use letters, numbers, '.', '_' or '-'; '.' and '..' alone are not valid names")
        String name,
        @NotNull @Size(max = 10000) List<@NotNull @Size(max = 256) String> values) { }
