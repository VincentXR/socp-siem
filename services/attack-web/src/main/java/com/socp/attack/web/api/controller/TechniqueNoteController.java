package com.socp.attack.web.api.controller;

import com.socp.attack.web.api.request.TechniqueNoteRequest;
import com.socp.attack.web.service.TechniqueNoteService;
import com.socp.platform.auth.security.RequireRole;
import com.socp.platform.audit.api.AuditOperation;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

@RestController
@RequestMapping("/api/v1/techniques/{id}/note")
public class TechniqueNoteController {
    private final TechniqueNoteService notes;

    public TechniqueNoteController(TechniqueNoteService notes) {
        this.notes = notes;
    }

    @GetMapping
    public Map<String, String> get(@PathVariable String id) {
        return Map.of("note", notes.get(id));
    }

    @PutMapping
    @RequireRole({"admin", "analyst"})
    @AuditOperation(action = "UPDATE_ATTACK_NOTE", target = "attack")
    public Map<String, String> put(@PathVariable String id, @Valid @RequestBody TechniqueNoteRequest request) {
        return Map.of("note", notes.save(id, request.note()));
    }
}
