package tech.calcifer.auth.state;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import org.junit.jupiter.api.Test;


class AuthorizationStateRecoveryTest {

    @Test
    void stableConnectivityAdvancesExactlyOnceAndDiscardsIsolation() {
        InMemoryRedisByteStore bytes = new InMemoryRedisByteStore();
        bytes.available(false);
        AuthorizationStateRoutingTest.MutableClock clock = new AuthorizationStateRoutingTest.MutableClock();
        AuthorizationStateManager state = AuthorizationStateRoutingTest.manager(
            bytes,
            AuthorizationStateRoutingTest.properties(AuthorizationStateProperties.Role.HOME, 1),
            clock
        );
        state.routeForStatefulRequest();
        String oldEpoch = state.isolationEpoch();

        bytes.available(true);
        state.probe();
        clock.advance(Duration.ofSeconds(2));
        state.probe();
        state.probe();

        assertThat(state.mode()).isEqualTo(AuthorizationStateManager.Mode.CONNECTED);
        assertThat(state.generation()).isEqualTo(2);
        assertThat(state.isolationEpoch()).isNull();
        assertThat(bytes.advances()).isEqualTo(1);
        assertThat(oldEpoch).isNotBlank();
    }

    @Test
    void lostCommitResponseIsResolvedWithoutSecondAdvance() {
        InMemoryRedisByteStore bytes = new InMemoryRedisByteStore();
        bytes.available(false);
        AuthorizationStateRoutingTest.MutableClock clock = new AuthorizationStateRoutingTest.MutableClock();
        AuthorizationStateManager state = AuthorizationStateRoutingTest.manager(
            bytes,
            AuthorizationStateRoutingTest.properties(AuthorizationStateProperties.Role.HOME, 1),
            clock
        );
        state.routeForStatefulRequest();
        bytes.available(true);
        bytes.loseAdvanceResponse();

        state.probe();
        clock.advance(Duration.ofSeconds(2));
        state.probe();
        state.probe();

        assertThat(state.mode()).isEqualTo(AuthorizationStateManager.Mode.CONNECTED);
        assertThat(state.generation()).isEqualTo(2);
        assertThat(bytes.advances()).isEqualTo(1);
    }

    @Test
    void failedOwnerExpiresAndLaterRecoveryRemainsSafe() {
        InMemoryRedisByteStore bytes = new InMemoryRedisByteStore();
        bytes.available(false);
        AuthorizationStateRoutingTest.MutableClock clock = new AuthorizationStateRoutingTest.MutableClock();
        AuthorizationStateManager state = AuthorizationStateRoutingTest.manager(
            bytes,
            AuthorizationStateRoutingTest.properties(AuthorizationStateProperties.Role.HOME, 1),
            clock
        );
        state.routeForStatefulRequest();
        bytes.available(true);
        bytes.rejectAdvance(true);
        state.probe();
        clock.advance(Duration.ofSeconds(2));
        state.probe();

        assertThat(state.mode()).isEqualTo(AuthorizationStateManager.Mode.ISOLATED);
        assertThat(bytes.advances()).isZero();
        bytes.expire("auth:control:gate");
        bytes.expire("auth:control:lease");
        bytes.rejectAdvance(false);
        state.probe();
        clock.advance(Duration.ofSeconds(2));
        state.probe();

        assertThat(state.generation()).isEqualTo(2);
        assertThat(bytes.advances()).isEqualTo(1);
    }

    @Test
    void reconnectFlappingRestartsStableSuccessWindow() {
        InMemoryRedisByteStore bytes = new InMemoryRedisByteStore();
        bytes.available(false);
        AuthorizationStateRoutingTest.MutableClock clock = new AuthorizationStateRoutingTest.MutableClock();
        AuthorizationStateManager state = AuthorizationStateRoutingTest.manager(
            bytes,
            AuthorizationStateRoutingTest.properties(AuthorizationStateProperties.Role.HOME, 1),
            clock
        );
        state.routeForStatefulRequest();

        bytes.available(true);
        state.probe();
        clock.advance(Duration.ofSeconds(1));
        bytes.available(false);
        state.probe();
        bytes.available(true);
        state.probe();
        clock.advance(Duration.ofSeconds(2));
        state.probe();
        state.probe();

        assertThat(state.mode()).isEqualTo(AuthorizationStateManager.Mode.CONNECTED);
        assertThat(bytes.advances()).isEqualTo(1);
    }
}
