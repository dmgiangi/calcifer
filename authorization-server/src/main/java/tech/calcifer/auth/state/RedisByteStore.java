package tech.calcifer.auth.state;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import org.springframework.data.redis.connection.RedisConnection;
import org.springframework.data.redis.connection.RedisConnectionFactory;
import org.springframework.data.redis.connection.ReturnType;
import org.springframework.data.redis.connection.RedisStringCommands.SetOption;
import org.springframework.data.redis.core.ScanOptions;
import org.springframework.data.redis.core.types.Expiration;

class RedisByteStore {
  private final RedisConnectionFactory connections;

  RedisByteStore(RedisConnectionFactory connections) {
    this.connections = connections;
  }

  byte[] get(String key) {
    try (RedisConnection connection = connections.getConnection()) {
      return connection.stringCommands().get(bytes(key));
    }
  }

  void set(String key, byte[] value) {
    try (RedisConnection connection = connections.getConnection()) {
      connection.stringCommands().set(bytes(key), value);
    }
  }

  void set(String key, byte[] value, Duration ttl) {
    try (RedisConnection connection = connections.getConnection()) {
      connection.stringCommands().set(bytes(key), value, Expiration.milliseconds(ttl.toMillis()), SetOption.UPSERT);
    }
  }

  boolean setIfAbsent(String key, byte[] value, Duration ttl) {
    try (RedisConnection connection = connections.getConnection()) {
      return Boolean.TRUE.equals(connection.stringCommands().set(bytes(key), value,
          Expiration.milliseconds(ttl.toMillis()), SetOption.SET_IF_ABSENT));
    }
  }

  boolean setIfAbsent(String key, byte[] value) {
    try (RedisConnection connection = connections.getConnection()) {
      return Boolean.TRUE.equals(connection.stringCommands().setNX(bytes(key), value));
    }
  }

  long eval(String script, List<String> keys, List<byte[]> arguments) {
    byte[][] values = new byte[keys.size() + arguments.size()][];
    for (int i = 0; i < keys.size(); i++) values[i] = bytes(keys.get(i));
    for (int i = 0; i < arguments.size(); i++) values[keys.size() + i] = arguments.get(i);
    try (RedisConnection connection = connections.getConnection()) {
      Number result = connection.scriptingCommands().eval(bytes(script), ReturnType.INTEGER, keys.size(), values);
      return result == null ? 0 : result.longValue();
    }
  }

  List<String> scan(String pattern, int limit) {
    List<String> keys = new ArrayList<>(limit);
    try (RedisConnection connection = connections.getConnection();
         var cursor = connection.keyCommands().scan(ScanOptions.scanOptions().match(pattern).count(limit).build())) {
      while (cursor.hasNext() && keys.size() < limit) {
        keys.add(new String(cursor.next(), StandardCharsets.UTF_8));
      }
    }
    return keys;
  }

  long unlink(List<String> keys) {
    if (keys.isEmpty()) return 0;
    byte[][] encoded = keys.stream().map(RedisByteStore::bytes).toArray(byte[][]::new);
    try (RedisConnection connection = connections.getConnection()) {
      Long removed = connection.keyCommands().unlink(encoded);
      return removed == null ? 0 : removed;
    }
  }

  static byte[] bytes(String value) {
    return value.getBytes(StandardCharsets.UTF_8);
  }
}
