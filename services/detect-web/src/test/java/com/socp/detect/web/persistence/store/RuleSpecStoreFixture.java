package com.socp.detect.web.persistence.store;

import com.socp.detect.web.persistence.repository.RuleContentConflictRepository;
import com.socp.detect.web.persistence.repository.RuleRepository;
import com.socp.detect.web.persistence.repository.RuleRevisionRepository;

import java.util.HashSet;
import java.util.function.Supplier;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/** Pure repository-mock fixtures; actual locking/rollback is covered by database tests. */
final class RuleSpecStoreFixture {
    private RuleSpecStoreFixture() { }

    static RuleSpecStore create(RuleRepository repository, RuleRevisionRepository revisions,
                                RuleContentConflictRepository conflicts) {
        RuleCatalogCoordinator catalog = mock(RuleCatalogCoordinator.class);
        var initialized = new HashSet<String>();
        when(catalog.withCatalog(anyString(), any(), any(), any())).thenAnswer(invocation -> {
            String tenant = invocation.getArgument(0);
            if (!initialized.contains(tenant)) {
                invocation.<Runnable>getArgument(2).run();
                initialized.add(tenant);
            }
            return invocation.<Supplier<?>>getArgument(3).get();
        });
        return new RuleSpecStore(repository, revisions, conflicts, catalog);
    }
}
