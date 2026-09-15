package tech.calcifer.auth.state;

import java.time.Duration;
import java.util.UUID;
import java.util.concurrent.ConcurrentMap;
import org.springframework.core.serializer.support.SerializationFailedException;
import org.springframework.session.MapSession;
import org.springframework.session.SessionRepository;

final class RoutingSessionRepository implements SessionRepository<MapSession> {
  private final AuthorizationStateManager state;
  private final RedisByteStore redis;
  private final String namespace;
  private final Duration defaultTimeout;
  private final VersionedStateSerializer<MapSession> serializer = new VersionedStateSerializer<>(MapSession.class);

  RoutingSessionRepository(AuthorizationStateManager state, RedisByteStore redis, String namespace,
      Duration defaultTimeout) {
    this.state = state;
    this.redis = redis;
    this.namespace = namespace;
    this.defaultTimeout = defaultTimeout;
  }

  @Override
  public MapSession createSession() {
    StateRoute route = writableRoute();
    MapSession session = new MapSession(() -> route.identifierPrefix() + UUID.randomUUID());
    session.setMaxInactiveInterval(defaultTimeout);
    return session;
  }

  @Override
  public void save(MapSession session) {
    StateRoute route = writableRoute();
    if (!session.getId().startsWith(route.identifierPrefix())) {
      throw new StateUnavailableException("Session does not belong to the request state route");
    }
    try {
      if (route.owner() == StateRoute.Owner.REDIS) {
        if (!session.getOriginalId().equals(session.getId())) redis.unlink(java.util.List.of(key(route, session.getOriginalId())));
        redis.set(key(route, session.getId()), serializer.serialize(new MapSession(session)), session.getMaxInactiveInterval());
        state.connectedOperationSucceeded();
      } else {
        ConcurrentMap<String, MapSession> sessions = state.localSessions(route);
        if (!session.getOriginalId().equals(session.getId())) sessions.remove(session.getOriginalId());
        sessions.put(session.getId(), new MapSession(session));
      }
    } catch (StateUnavailableException exception) {
      if (route.owner() == StateRoute.Owner.REDIS) state.connectedOperationFailed();
      throw exception;
    } catch (RuntimeException exception) {
      if (route.owner() == StateRoute.Owner.REDIS) state.connectedOperationFailed();
      throw new StateUnavailableException("Session state operation failed", exception);
    }
  }

  @Override
  public MapSession findById(String id) {
    StateRoute route = RequestStateContext.requireRoute();
    if (route.owner() == StateRoute.Owner.UNAVAILABLE || !id.startsWith(route.identifierPrefix())) return null;
    try {
      MapSession session = route.owner() == StateRoute.Owner.REDIS ? fromRedis(route, id)
          : copy(state.localSessions(route).get(id));
      if (session != null && session.isExpired()) {
        deleteById(id);
        return null;
      }
      if (session != null) session.setSessionIdGenerator(() -> route.identifierPrefix() + UUID.randomUUID());
      if (route.owner() == StateRoute.Owner.REDIS) state.connectedOperationSucceeded();
      return session;
    } catch (StateUnavailableException exception) {
      if (route.owner() == StateRoute.Owner.REDIS) state.connectedOperationFailed();
      throw exception;
    } catch (RuntimeException exception) {
      if (route.owner() == StateRoute.Owner.REDIS) state.connectedOperationFailed();
      throw new StateUnavailableException("Session state operation failed", exception);
    }
  }

  @Override
  public void deleteById(String id) {
    StateRoute route = RequestStateContext.requireRoute();
    if (route.owner() == StateRoute.Owner.UNAVAILABLE || !id.startsWith(route.identifierPrefix())) return;
    try {
      if (route.owner() == StateRoute.Owner.REDIS) {
        redis.unlink(java.util.List.of(key(route, id)));
        state.connectedOperationSucceeded();
      } else state.localSessions(route).remove(id);
    } catch (RuntimeException exception) {
      if (route.owner() == StateRoute.Owner.REDIS) state.connectedOperationFailed();
      throw new StateUnavailableException("Session state operation failed", exception);
    }
  }

  private StateRoute writableRoute() {
    StateRoute route = RequestStateContext.requireRoute();
    if (route.owner() == StateRoute.Owner.UNAVAILABLE) {
      throw new StateUnavailableException("Session state is temporarily unavailable");
    }
    return route;
  }

  private MapSession fromRedis(StateRoute route, String id) {
    byte[] encoded = redis.get(key(route, id));
    if (encoded == null) return null;
    try {
      return serializer.deserialize(encoded);
    } catch (SerializationFailedException exception) {
      // A session payload can outlive a native-image serialization hint or deployment
      // change. It is safe to discard that browser session and require fresh login;
      // Redis connectivity failures are deliberately not caught here.
      redis.unlink(java.util.List.of(key(route, id)));
      state.connectedOperationSucceeded();
      return null;
    }
  }

  private MapSession copy(MapSession session) {
    return session == null ? null : new MapSession(session);
  }

  private String key(StateRoute route, String id) {
    return namespace + ":g" + route.generation() + ":session:" + id;
  }
}
