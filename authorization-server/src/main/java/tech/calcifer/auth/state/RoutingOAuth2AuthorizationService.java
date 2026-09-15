package tech.calcifer.auth.state;

import java.util.Objects;
import org.springframework.security.oauth2.server.authorization.OAuth2Authorization;
import org.springframework.security.oauth2.server.authorization.OAuth2AuthorizationService;
import org.springframework.security.oauth2.server.authorization.OAuth2TokenType;

final class RoutingOAuth2AuthorizationService implements OAuth2AuthorizationService {
  private final AuthorizationStateManager state;
  private final RedisOAuth2AuthorizationStore redis;

  RoutingOAuth2AuthorizationService(AuthorizationStateManager state, RedisOAuth2AuthorizationStore redis) {
    this.state = state;
    this.redis = redis;
  }

  @Override
  public void save(OAuth2Authorization authorization) {
    Objects.requireNonNull(authorization, "authorization");
    execute(route -> {
      if (route.owner() == StateRoute.Owner.REDIS) redis.save(route.generation(), authorization);
      else state.localAuthorizations(route).save(authorization);
      return null;
    });
  }

  @Override
  public void remove(OAuth2Authorization authorization) {
    Objects.requireNonNull(authorization, "authorization");
    execute(route -> {
      if (route.owner() == StateRoute.Owner.REDIS) redis.remove(route.generation(), authorization);
      else state.localAuthorizations(route).remove(authorization);
      return null;
    });
  }

  @Override
  public OAuth2Authorization findById(String id) {
    return execute(route -> route.owner() == StateRoute.Owner.REDIS
        ? redis.findById(route.generation(), id) : state.localAuthorizations(route).findById(id));
  }

  @Override
  public OAuth2Authorization findByToken(String token, OAuth2TokenType tokenType) {
    return execute(route -> route.owner() == StateRoute.Owner.REDIS
        ? redis.findByToken(route.generation(), token, tokenType)
        : state.localAuthorizations(route).findByToken(token, tokenType));
  }

  private <T> T execute(RouteOperation<T> operation) {
    StateRoute route = RequestStateContext.requireRoute();
    if (route.owner() == StateRoute.Owner.UNAVAILABLE) {
      throw new StateUnavailableException("Authorization state is temporarily unavailable");
    }
    try {
      T result = operation.apply(route);
      if (route.owner() == StateRoute.Owner.REDIS) state.connectedOperationSucceeded();
      return result;
    } catch (StateUnavailableException exception) {
      if (route.owner() == StateRoute.Owner.REDIS) state.connectedOperationFailed();
      throw exception;
    } catch (RuntimeException exception) {
      if (route.owner() == StateRoute.Owner.REDIS) state.connectedOperationFailed();
      throw new StateUnavailableException("Authorization state operation failed", exception);
    }
  }

  @FunctionalInterface
  private interface RouteOperation<T> {
    T apply(StateRoute route);
  }
}
