package tech.calcifer.auth.state;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import org.junit.jupiter.api.Test;


class RedisControlRepositoryTest {

    @Test
    void leaseGateAndCompareAdvanceAreOwnerBoundAndAtomic() {
        InMemoryRedisByteStore store = new InMemoryRedisByteStore();
        RedisControlRepository repository = new RedisControlRepository(store, "auth");

        assertThat(repository.read().generation()).isEqualTo(1);
        assertThat(repository.acquireLease("owner-a", Duration.ofSeconds(30))).isTrue();
        assertThat(repository.acquireLease("owner-b", Duration.ofSeconds(30))).isFalse();
        assertThat(repository.acquireGate("owner-b", Duration.ofSeconds(20))).isFalse();
        assertThat(repository.acquireGate("owner-a", Duration.ofSeconds(20))).isTrue();
        assertThat(repository.compareAndAdvance("owner-b", 1)).isZero();
        assertThat(repository.compareAndAdvance("owner-a", 1)).isEqualTo(2);

        assertThat(repository.committed("owner-a", 1)).isTrue();
        assertThat(repository.read().gateOwner()).isNull();
        assertThat(store.keys())
            .contains("auth:control:generation", "auth:control:last-recovery")
            .doesNotContain("auth:control:gate", "auth:control:lease");
    }

    @Test
    void expiredGateLeavesGenerationUnchanged() {
        InMemoryRedisByteStore store = new InMemoryRedisByteStore();
        RedisControlRepository repository = new RedisControlRepository(store, "auth");
        repository.read();
        repository.acquireLease("owner", Duration.ofSeconds(30));
        repository.acquireGate("owner", Duration.ofSeconds(20));

        store.expire("auth:control:gate");
        store.expire("auth:control:lease");

        assertThat(repository.read().generation()).isEqualTo(1);
        assertThat(repository.read().gateOwner()).isNull();
    }
}
