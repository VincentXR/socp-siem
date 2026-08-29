package com.socp.platform.tenant.persistence;

import org.junit.jupiter.api.Test;

import javax.sql.DataSource;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

class TenantRlsDataSourcePostProcessorTest {

    @Test
    void decoratesOnlyUnwrappedDataSources() {
        TenantRlsDataSourcePostProcessor processor = new TenantRlsDataSourcePostProcessor();
        DataSource delegate = mock(DataSource.class);

        Object wrapped = processor.postProcessAfterInitialization(delegate, "dataSource");
        Object unchanged = processor.postProcessAfterInitialization(wrapped, "dataSource");
        Object other = processor.postProcessAfterInitialization(new Object(), "other");

        assertThat(wrapped).isInstanceOf(TenantRlsDataSource.class);
        assertThat(unchanged).isSameAs(wrapped);
        assertThat(other).isNotNull();
    }
}
