package tech.calcifer.auth.state;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

final class InMemoryRedisByteStore extends RedisByteStore {
  private final Map<String, byte[]> values = new LinkedHashMap<>();
  private boolean available = true;
  private boolean loseAdvanceResponse;
  private boolean rejectAdvance;
  private int advances;

  InMemoryRedisByteStore() {
    super(null);
  }

  void available(boolean value) { available = value; }
  void loseAdvanceResponse() { loseAdvanceResponse = true; }
  void rejectAdvance(boolean value) { rejectAdvance = value; }
  int advances() { return advances; }
  Set<String> keys() { return Set.copyOf(values.keySet()); }
  void expire(String key) { values.remove(key); }

  @Override byte[] get(String key) {
    checkAvailable();
    byte[] value = values.get(key);
    return value == null ? null : value.clone();
  }

  @Override void set(String key, byte[] value) {
    checkAvailable();
    values.put(key, value.clone());
  }

  @Override void set(String key, byte[] value, Duration ttl) { set(key, value); }

  @Override boolean setIfAbsent(String key, byte[] value, Duration ttl) {
    return setIfAbsent(key, value);
  }

  @Override boolean setIfAbsent(String key, byte[] value) {
    checkAvailable();
    return values.putIfAbsent(key, value.clone()) == null;
  }

  @Override long eval(String script, List<String> keys, List<byte[]> arguments) {
    checkAvailable();
    if (keys.size() == 2 && arguments.size() == 2) return acquireGate(keys, arguments);
    if (keys.size() == 4 && arguments.size() == 2) return advance(keys, arguments);
    if (arguments.size() == 4) return saveAuthorization(keys, arguments);
    keys.forEach(values::remove);
    return keys.size();
  }

  @Override List<String> scan(String pattern, int limit) {
    checkAvailable();
    String prefix = pattern.substring(0, pattern.indexOf('*'));
    String suffix = pattern.substring(pattern.lastIndexOf('*') + 1);
    return values.keySet().stream().filter(key -> key.startsWith(prefix) && key.endsWith(suffix))
        .limit(limit).toList();
  }

  @Override long unlink(List<String> keys) {
    checkAvailable();
    return keys.stream().filter(key -> values.remove(key) != null).count();
  }

  private long acquireGate(List<String> keys, List<byte[]> arguments) {
    String owner = text(arguments.getFirst());
    if (!owner.equals(text(values.get(keys.getFirst())))) return 0;
    String current = text(values.get(keys.get(1)));
    if (current != null && !owner.equals(current)) return 0;
    values.put(keys.get(1), arguments.getFirst().clone());
    return 1;
  }

  private long advance(List<String> keys, List<byte[]> arguments) {
    String owner = text(arguments.getFirst());
    String expected = text(arguments.get(1));
    if (rejectAdvance || !owner.equals(text(values.get(keys.get(0))))
        || !owner.equals(text(values.get(keys.get(1))))
        || !expected.equals(text(values.get(keys.get(2))))) return 0;
    long next = Long.parseLong(expected) + 1;
    values.put(keys.get(2), bytes(Long.toString(next)));
    values.put(keys.get(3), arguments.getFirst().clone());
    values.remove(keys.get(0));
    values.remove(keys.get(1));
    advances++;
    if (loseAdvanceResponse) {
      loseAdvanceResponse = false;
      throw new StateUnavailableException("simulated lost response");
    }
    return next;
  }

  private long saveAuthorization(List<String> keys, List<byte[]> arguments) {
    int oldCount = Integer.parseInt(text(arguments.get(1)));
    for (int i = 1; i <= oldCount; i++) values.remove(keys.get(i));
    values.put(keys.getFirst(), arguments.getFirst().clone());
    for (int i = oldCount + 1; i < keys.size(); i++) values.put(keys.get(i), arguments.get(2).clone());
    return 1;
  }

  private void checkAvailable() {
    if (!available) throw new StateUnavailableException("simulated Redis outage");
  }

  private static String text(byte[] value) {
    return value == null ? null : new String(value, StandardCharsets.UTF_8);
  }
}
