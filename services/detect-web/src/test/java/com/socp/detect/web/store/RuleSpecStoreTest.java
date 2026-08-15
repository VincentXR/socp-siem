package com.socp.detect.web.store;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import org.junit.jupiter.api.Test;

import static org.mockito.ArgumentMatchers.any;

class RuleSpecStoreTest {

    @Test
    void seedsDefaultRulesWithValidJson() {
        RuleRepository repository = mock(RuleRepository.class);
        when(repository.count()).thenReturn(0L);

        new RuleSpecStore(repository);

        verify(repository, org.mockito.Mockito.atLeastOnce()).save(any(RuleEntity.class));
    }
}
