package io.github.dornol.idkit;

import io.github.dornol.idkit.flake.FlakeIdGenerator;
import io.github.dornol.idkit.worker.LeasedIdGenerator;
import io.github.dornol.idkit.worker.RecoveringLeasedIdGenerator;
import io.github.dornol.idkit.worker.WorkerIdLease;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import java.util.concurrent.Executors;

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

    @Test
    void createsRecoveringGeneratorFromJava() {
        WorkerIdLease lease = new WorkerIdLease() {
            @Override public int getWorkerId() { return 1; }
            @Override public int getDatacenterId() { return 0; }
            @Override public void close() { }
        };
        var scheduler = Executors.newSingleThreadScheduledExecutor();
        try {
            RecoveringLeasedIdGenerator<Long> generator = RecoveringLeasedIdGenerator.create(
                    lease,
                    new IdGenerator<Long>() {
                        @Override public Long nextId() { return 1L; }
                    },
                    scheduler,
                    1000L,
                    () -> lease,
                    current -> new IdGenerator<Long>() {
                        @Override public Long nextId() { return (long) current.getWorkerId(); }
                    }
            );
            assertEquals(1L, generator.nextId());
            generator.close();
        } finally {
            scheduler.shutdownNow();
        }
    }
}
