package io.github.dornol.idkit.spring.boot.starter;

import io.github.dornol.idkit.jdbc.JdbcWorkerIdLeaseStore;
import io.github.dornol.idkit.spring.boot.JdbcIdKitAutoConfiguration;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertNotNull;

class JdbcStarterClasspathTest {
    @Test
    void starterExportsJdbcAndAutoConfigurationClasses() {
        assertNotNull(JdbcWorkerIdLeaseStore.class);
        assertNotNull(JdbcIdKitAutoConfiguration.class);
    }
}
