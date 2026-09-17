package tech.calcifer.auth.state;

import java.util.List;


final class RedisGenerationCleanup {

    private final RedisByteStore store;
    private final String namespace;
    private final int batchSize;

    RedisGenerationCleanup(RedisByteStore store, String namespace, int batchSize) {
        this.store = store;
        this.namespace = namespace;
        this.batchSize = batchSize;
    }

    long cleanup(long activeGeneration) {
        List<String> candidates = store.scan(namespace + ":g*:*", batchSize);
        List<String> obsolete = candidates
            .stream()
            .filter(key -> generationOf(key) > 0 && generationOf(key) < activeGeneration)
            .limit(batchSize)
            .toList();
        return store.unlink(obsolete);
    }

    private long generationOf(String key) {
        String prefix = namespace + ":g";
        if (!key.startsWith(prefix)) {
            return -1;
        }
        int separator = key.indexOf(':', prefix.length());
        if (separator < 0) {
            return -1;
        }
        try {
            return Long.parseLong(key.substring(prefix.length(), separator));
        } catch (NumberFormatException ignored) {
            return -1;
        }
    }
}
