package com.socp.attack.web.api.request;

import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

public record TechniqueNoteRequest(@NotNull @Size(max = 4000) String note) { }
