package tech.calcifer.auth.state;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;

class RedisControlRepository {
  private static final String ACQUIRE_GATE = """
      if redis.call('GET', KEYS[1]) == ARGV[1] then
        local gate = redis.call('GET', KEYS[2])
        if (not gate) or gate == ARGV[1] then
          redis.call('SET', KEYS[2], ARGV[1], 'PX', ARGV[2])
          return 1
        end
      end
      return 0
      """;
  private static final String ADVANCE = """
      if redis.call('GET', KEYS[1]) ~= ARGV[1] then return 0 end
      if redis.call('GET', KEYS[2]) ~= ARGV[1] then return 0 end
      if redis.call('GET', KEYS[3]) ~= ARGV[2] then return 0 end
      local next = tonumber(ARGV[2]) + 1
      redis.call('SET', KEYS[3], tostring(next))
      redis.call('SET', KEYS[4], ARGV[1])
      redis.call('DEL', KEYS[2])
      redis.call('DEL', KEYS[1])
      return next
      """;

  record ControlState(long generation, String gateOwner, String lastRecoveryOwner) {}

  private final RedisByteStore store;
  private final String prefix;

  RedisControlRepository(RedisByteStore store, String namespace) {
    this.store = store;
    this.prefix = namespace + ":control:";
  }

  ControlState read() {
    byte[] generation = store.get(key("generation"));
    if (generation == null) {
      store.setIfAbsent(key("generation"), RedisByteStore.bytes("1"));
      generation = store.get(key("generation"));
    }
    long parsed;
    try {
      parsed = Long.parseLong(text(generation));
    } catch (RuntimeException exception) {
      throw new StateUnavailableException("Redis generation is invalid", exception);
    }
    if (parsed < 1) throw new StateUnavailableException("Redis generation is invalid");
    return new ControlState(parsed, text(store.get(key("gate"))), text(store.get(key("last-recovery"))));
  }

  boolean acquireLease(String owner, Duration ttl) {
    return store.setIfAbsent(key("lease"), RedisByteStore.bytes(owner), ttl);
  }

  boolean acquireGate(String owner, Duration ttl) {
    return store.eval(ACQUIRE_GATE, List.of(key("lease"), key("gate")),
        List.of(RedisByteStore.bytes(owner), RedisByteStore.bytes(Long.toString(ttl.toMillis())))) == 1;
  }

  long compareAndAdvance(String owner, long expectedGeneration) {
    return store.eval(ADVANCE,
        List.of(key("lease"), key("gate"), key("generation"), key("last-recovery")),
        List.of(RedisByteStore.bytes(owner), RedisByteStore.bytes(Long.toString(expectedGeneration))));
  }

  boolean committed(String owner, long expectedGeneration) {
    ControlState state = read();
    return state.generation() == expectedGeneration + 1 && owner.equals(state.lastRecoveryOwner());
  }

  private String key(String suffix) {
    return prefix + suffix;
  }

  private static String text(byte[] value) {
    return value == null ? null : new String(value, StandardCharsets.UTF_8);
  }
}
