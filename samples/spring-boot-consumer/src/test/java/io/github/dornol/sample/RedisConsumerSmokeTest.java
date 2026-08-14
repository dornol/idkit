package io.github.dornol.sample;

import io.github.dornol.idkit.IdGenerator;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.annotation.DirtiesContext;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import static org.junit.jupiter.api.Assertions.assertTrue;

@Testcontainers
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
@SpringBootTest(
    classes = ConsumerApplication.class,
    properties = {
        "idkit.backend=redis",
        "spring.autoconfigure.exclude=org.springframework.boot.autoconfigure.jdbc.DataSourceAutoConfiguration",
        "idkit.redis.key-prefix=idkit:sample:redis"
    }
)
class RedisConsumerSmokeTest {
    @Container
    static final GenericContainer<?> REDIS =
        new GenericContainer<>("redis:7-alpine")
            .withExposedPorts(6379);

    @DynamicPropertySource
    static void redisProperties(DynamicPropertyRegistry registry) {
        registry.add(
            "idkit.redis.uri",
            () -> "redis://" + REDIS.getHost() + ":" + REDIS.getMappedPort(6379)
        );
    }

    @Autowired
    private IdGenerator<Long> idGenerator;

    @Test
    void injectsAndGeneratesLongId() {
        assertTrue(idGenerator.nextId() > 0);
    }
}
