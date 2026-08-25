package com.socp.report.web.persistence.store;

import com.socp.report.web.persistence.store.*;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class ReportObjectStoreTest {

    @Test
    void disabledObjectStorageIsAnExplicitNoOp() {
        ReportObjectStore store = new ReportObjectStore(
                "http://localhost:9000", "key", "secret", "reports", false);

        assertThat(store.put("reports/a.json", "{}", "application/json")).isNull();
        assertThat(store.list("reports/")).isEmpty();
        assertThat(store.presignedGet("reports/a.json")).isNull();
        assertThat(store.remove("reports/a.json")).isFalse();
        assertThat(ReportObjectStore.today()).matches("\\d{8}");
    }
}
