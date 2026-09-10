package com.socp.attack.web.service;

import com.socp.attack.web.persistence.entity.TechniqueNoteEntity;
import com.socp.attack.web.persistence.repository.TechniqueNoteRepository;
import com.socp.attack.web.persistence.store.AttackStore;
import com.socp.platform.tenant.context.TenantContext;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

@Service
public class TechniqueNoteService {
    private final TechniqueNoteRepository notes;
    private final AttackStore catalog;

    public TechniqueNoteService(TechniqueNoteRepository notes, AttackStore catalog) {
        this.notes = notes;
        this.catalog = catalog;
    }

    public String get(String id) {
        requireTechnique(id);
        return notes.findByTenantIdAndTechniqueId(TenantContext.require(), id)
                .map(TechniqueNoteEntity::getNote).orElse("");
    }

    @Transactional
    public String save(String id, String text) {
        requireTechnique(id);
        String tenant = TenantContext.require();
        TechniqueNoteEntity note = notes.findByTenantIdAndTechniqueId(tenant, id)
                .orElseGet(() -> new TechniqueNoteEntity(tenant, id));
        note.setNote(text);
        notes.save(note);
        return note.getNote();
    }

    private void requireTechnique(String id) {
        if (catalog.technique(id) == null) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Technique not found");
        }
    }
}
