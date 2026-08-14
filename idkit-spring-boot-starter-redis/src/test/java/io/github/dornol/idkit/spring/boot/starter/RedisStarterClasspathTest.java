package io.github.dornol.idkit.spring.boot.starter;

import io.github.dornol.idkit.redis.RedisWorkerIdLeaseStore;
import io.github.dornol.idkit.spring.boot.RedisIdKitAutoConfiguration;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertNotNull;

class RedisStarterClasspathTest {
    @Test
    void starterExportsRedisAndAutoConfigurationClasses() {
        assertNotNull(RedisWorkerIdLeaseStore.class);
        assertNotNull(RedisIdKitAutoConfiguration.class);
    }
}
