package io.github.dornol.sample;

import io.github.dornol.idkit.IdGenerator;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.annotation.DirtiesContext;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import static org.junit.jupiter.api.Assertions.assertTrue;

@Testcontainers
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
@SpringBootTest(
    classes = ConsumerApplication.class,
    properties = {
        "idkit.backend=jdbc",
        "idkit.jdbc.auto-initialize=true",
        "idkit.jdbc.table-name=idkit_sample_jdbc_lease"
    }
)
class JdbcConsumerSmokeTest {
    @Container
    static final PostgreSQLContainer<?> POSTGRES =
        new PostgreSQLContainer<>("postgres:16-alpine")
            .withDatabaseName("idkit")
            .withUsername("idkit")
            .withPassword("idkit");

    @DynamicPropertySource
    static void postgresProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        registry.add("spring.datasource.username", POSTGRES::getUsername);
        registry.add("spring.datasource.password", POSTGRES::getPassword);
    }

    @Autowired
    private IdGenerator<Long> idGenerator;

    @Test
    void injectsAndGeneratesLongId() {
        assertTrue(idGenerator.nextId() > 0);
    }
}
