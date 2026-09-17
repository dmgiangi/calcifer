package tech.calcifer.auth.state;

import io.micrometer.observation.Observation;
import io.micrometer.observation.ObservationRegistry;
import java.util.Objects;
import org.springframework.security.oauth2.server.authorization.OAuth2Authorization;
import org.springframework.security.oauth2.server.authorization.OAuth2AuthorizationService;
import org.springframework.security.oauth2.server.authorization.OAuth2TokenType;


final class RoutingOAuth2AuthorizationService implements OAuth2AuthorizationService {

    private final AuthorizationStateManager state;
    private final RedisOAuth2AuthorizationStore redis;
    private final ObservationRegistry observations;

    RoutingOAuth2AuthorizationService(AuthorizationStateManager state, RedisOAuth2AuthorizationStore redis) {
        this(state, redis, ObservationRegistry.NOOP);
    }

    RoutingOAuth2AuthorizationService(
        AuthorizationStateManager state,
        RedisOAuth2AuthorizationStore redis,
        ObservationRegistry observations) {
        this.state = state;
        this.redis = redis;
        this.observations = observations;
    }

    @Override
    public void save(OAuth2Authorization authorization) {
        Objects.requireNonNull(authorization, "authorization");
        execute(
            "save", route -> {
                if (route.owner() == StateRoute.Owner.REDIS) {
                    redis.save(route.generation(), authorization);
                } else {
                    state.localAuthorizations(route).save(authorization);
                }
                return null;
            }
        );
    }

    @Override
    public void remove(OAuth2Authorization authorization) {
        Objects.requireNonNull(authorization, "authorization");
        execute(
            "remove", route -> {
                if (route.owner() == StateRoute.Owner.REDIS) {
                    redis.remove(route.generation(), authorization);
                } else {
                    state.localAuthorizations(route).remove(authorization);
                }
                return null;
            }
        );
    }

    @Override
    public OAuth2Authorization findById(String id) {
        return execute(
            "find-by-id",
            route -> route.owner() == StateRoute.Owner.REDIS ? redis.findById(route.generation(), id)
                : state.localAuthorizations(route).findById(id)
        );
    }

    @Override
    public OAuth2Authorization findByToken(String token, OAuth2TokenType tokenType) {
        return execute(
            "find-by-token",
            route -> route.owner() == StateRoute.Owner.REDIS ? redis.findByToken(route.generation(), token, tokenType)
                : state.localAuthorizations(route).findByToken(token, tokenType)
        );
    }

    private <T> T execute(String operationName, RouteOperation<T> operation) {
        StateRoute route = RequestStateContext.requireRoute();
        Observation observation = Observation
            .createNotStarted("authorization.state.repository", observations)
            .contextualName("authorization state " + operationName)
            .lowCardinalityKeyValue("authorization.state.operation", operationName)
            .lowCardinalityKeyValue("authorization.state.owner", route.owner().name())
            .highCardinalityKeyValue("authorization.state.generation", Long.toString(route.generation()))
            .start();
        try (Observation.Scope ignored = observation.openScope()) {
            if (route.owner() == StateRoute.Owner.UNAVAILABLE) {
                StateUnavailableException failure = new StateUnavailableException(
                    "Authorization state is temporarily unavailable");
                recordFailure(observation, failure);
                throw failure;
            }
            try {
                T result = operation.apply(route);
                if (route.owner() == StateRoute.Owner.REDIS) {
                    state.connectedOperationSucceeded();
                }
                return result;
            } catch (StateUnavailableException exception) {
                if (route.owner() == StateRoute.Owner.REDIS) {
                    state.connectedOperationFailed();
                }
                recordFailure(observation, exception);
                throw exception;
            } catch (RuntimeException exception) {
                if (route.owner() == StateRoute.Owner.REDIS) {
                    state.connectedOperationFailed();
                }
                recordFailure(observation, exception);
                throw new StateUnavailableException("Authorization state operation failed", exception);
            }
        } finally {
            observation.stop();
        }
    }

    private void recordFailure(Observation observation, RuntimeException failure) {
        Throwable rootCause = failure;
        while (rootCause.getCause() != null) {
            rootCause = rootCause.getCause();
        }
        observation.highCardinalityKeyValue("error.root_cause.type", rootCause.getClass().getName());
        observation.error(failure);
    }

    @FunctionalInterface
    private interface RouteOperation<T> {

        T apply(StateRoute route);
    }
}
