package com.socp.attack.web.store;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.verify;

@ExtendWith(MockitoExtension.class)
class AttackStoreTest {

    @Mock
    private TacticRepository tacticRepository;

    @Mock
    private TechniqueRepository techniqueRepository;

    @Test
    void updatesTechniqueInMemoryAndRepository() {
        TechniqueEntity entity = new TechniqueEntity("T1110", "Brute Force", "TA0006", "old-url", "old description");
        given(tacticRepository.findAll()).willReturn(List.of(new TacticEntity("TA0006", "Credential Access", 1)));
        given(techniqueRepository.findAll()).willReturn(List.of(entity));
        given(techniqueRepository.findById("T1110")).willReturn(Optional.of(entity));
        AttackStore store = new AttackStore(tacticRepository, techniqueRepository);
        store.seed();

        var updated = store.update("T1110", "Password Spray", "TA0006", "new-url", "new description");

        assertNotNull(updated);
        assertEquals("Password Spray", updated.name());
        assertEquals("new-url", store.technique("T1110").url());
        assertEquals("new description", entity.getDescription());
        verify(techniqueRepository).save(any(TechniqueEntity.class));
    }
}
