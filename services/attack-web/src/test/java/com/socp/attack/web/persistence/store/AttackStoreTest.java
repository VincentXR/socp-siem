package com.socp.attack.web.persistence.store;



import com.socp.attack.web.persistence.store.*;
import com.socp.attack.web.persistence.repository.*;
import com.socp.attack.web.persistence.entity.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;
import java.util.Optional;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

@ExtendWith(MockitoExtension.class)
class AttackStoreTest {

    @Mock
    private TacticRepository tacticRepository;

    @Mock
    private TechniqueRepository techniqueRepository;

    @Test
    void updatesTechniqueInRepositoryAndReadsItBack() {
        TechniqueEntity entity = new TechniqueEntity("T1110", "Brute Force", "TA0006", "old-url", "old description");
        given(tacticRepository.count()).willReturn(1L);
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

    @Test
    void seedsTheFullEnterpriseCatalogWhenTheDatabaseIsEmpty() {
        given(tacticRepository.count()).willReturn(0L);
        AttackStore store = new AttackStore(tacticRepository, techniqueRepository);

        store.seed();

        verify(tacticRepository, times(14)).save(any(TacticEntity.class));
        verify(techniqueRepository, times(37)).save(any(TechniqueEntity.class));
    }

    @Test
    void computesCoverageByTacticAndListsUncoveredTechniques() {
        given(tacticRepository.findAllByOrderBySortAsc()).willReturn(List.of(
                new TacticEntity("TA0001", "Initial Access", 1)));
        given(techniqueRepository.findAllByOrderByIdAsc()).willReturn(List.of(
                new TechniqueEntity("T1190", "Exploit", "TA0001", "url", "desc"),
                new TechniqueEntity("T1566", "Phishing", "TA0001", "url", "desc")));
        AttackStore store = new AttackStore(tacticRepository, techniqueRepository);

        var coverage = store.coverage(Set.of("T1190"));

        assertEquals(2, coverage.get("totalTechniques"));
        assertEquals(1, coverage.get("coveredTechniques"));
        assertEquals(50, coverage.get("coverage"));
        assertEquals(List.of("T1566"), coverage.get("uncovered"));
    }
}
