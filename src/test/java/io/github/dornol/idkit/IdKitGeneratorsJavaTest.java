package io.github.dornol.idkit;

import io.github.dornol.idkit.flake.FlakeIdGenerator;
import io.github.dornol.idkit.worker.LeasedIdGenerator;
import io.github.dornol.idkit.worker.WorkerIdLease;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class IdKitGeneratorsJavaTest {

    @Test
    void createsCustomFlakeGeneratorFromJava() {
        WorkerIdLease lease = new WorkerIdLease() {
            @Override
            public int getWorkerId() {
                return 3;
            }

            @Override
            public int getDatacenterId() {
                return 2;
            }

            @Override
            public void close() {
            }
        };

        LeasedIdGenerator<Long> generator = IdKitGenerators.flake(lease, 42, 4, 6);

        assertTrue(generator.nextId() > 0);
        assertNotNull(generator);
    }

    @Test
    void createsStandardSnowflakeGeneratorFromJava() {
        FlakeIdGenerator generator = IdKitGenerators.snowflake(3, 2);

        assertTrue(generator.nextId() > 0);
    }

    @Test
    void createsStringAndUuidGeneratorsFromJava() {
        assertEquals(26, IdKitGenerators.ulid().nextId().length());
        assertEquals(21, IdKitGenerators.nanoId().nextId().length());
        assertEquals(7, IdKitGenerators.uuidV7().nextId().version());
    }
}
