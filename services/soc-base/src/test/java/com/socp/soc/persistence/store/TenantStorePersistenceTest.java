package com.socp.soc.persistence.store;



import com.socp.soc.persistence.store.*;
import com.socp.soc.persistence.repository.*;
import com.socp.soc.persistence.entity.*;
import com.socp.soc.domain.TenantInfo;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.context.annotation.Import;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

@DataJpaTest
@Import(TenantStore.class)
class TenantStorePersistenceTest {

    @Autowired
    private TenantStore store;

    @Autowired
    private TenantRepository repository;

    @Test
    void writesAreVisibleThroughANewStoreAgainstTheSameDatabase() {
        TenantInfo saved = TenantInfo.create("Persistent tenant", "persistent-tenant");
        store.save(saved);

        TenantStore anotherInstance = new TenantStore(repository);
        TenantInfo reloaded = anotherInstance.get(saved.id());

        assertNotNull(reloaded);
        assertEquals(saved, reloaded);
        assertEquals(4, anotherInstance.list().size());
    }
}
