package tech.calcifer.auth.state;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.time.Duration;
import java.time.Instant;
import java.util.HexFormat;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import org.springframework.security.oauth2.core.OAuth2AccessToken;
import org.springframework.security.oauth2.core.OAuth2DeviceCode;
import org.springframework.security.oauth2.core.OAuth2RefreshToken;
import org.springframework.security.oauth2.core.OAuth2Token;
import org.springframework.security.oauth2.core.OAuth2UserCode;
import org.springframework.security.oauth2.core.oidc.OidcIdToken;
import org.springframework.security.oauth2.server.authorization.OAuth2Authorization;
import org.springframework.security.oauth2.server.authorization.OAuth2AuthorizationCode;
import org.springframework.security.oauth2.server.authorization.OAuth2TokenType;


final class RedisOAuth2AuthorizationStore {

    private static final String SAVE = """
        local old = tonumber(ARGV[2])
        for i = 2, old + 1 do redis.call('DEL', KEYS[i]) end
        redis.call('SET', KEYS[1], ARGV[1], 'PX', ARGV[4])
        for i = old + 2, #KEYS do redis.call('SET', KEYS[i], ARGV[3], 'PX', ARGV[4]) end
        return 1
        """;
    private static final String REMOVE = """
        for i = 1, #KEYS do redis.call('DEL', KEYS[i]) end
        return #KEYS
        """;
    private static final List<Class<? extends OAuth2Token>> TOKEN_TYPES = List.of(
        OAuth2AuthorizationCode.class,
        OAuth2AccessToken.class,
        OAuth2RefreshToken.class,
        OidcIdToken.class,
        OAuth2DeviceCode.class,
        OAuth2UserCode.class
    );

    private final RedisByteStore store;
    private final String namespace;
    private final VersionedStateSerializer<OAuth2Authorization> serializer = new VersionedStateSerializer<>(
        OAuth2Authorization.class);

    RedisOAuth2AuthorizationStore(RedisByteStore store, String namespace) {
        this.store = store;
        this.namespace = namespace;
    }

    void save(long generation, OAuth2Authorization authorization) {
        OAuth2Authorization previous = findById(generation, authorization.getId());
        Set<String> oldIndexes = previous == null ? Set.of() : tokenIndexKeys(generation, previous);
        Set<String> newIndexes = tokenIndexKeys(generation, authorization);
        List<String> keys = new ArrayList<>();
        keys.add(authorizationKey(generation, authorization.getId()));
        keys.addAll(oldIndexes);
        keys.addAll(newIndexes);
        store.eval(
            SAVE, keys, List.of(
                serializer.serialize(authorization),
                RedisByteStore.bytes(Integer.toString(oldIndexes.size())),
                RedisByteStore.bytes(authorization.getId()),
                RedisByteStore.bytes(Long.toString(expirationMillis(authorization)))
            )
        );
    }

    void remove(long generation, OAuth2Authorization authorization) {
        List<String> keys = new ArrayList<>();
        keys.add(authorizationKey(generation, authorization.getId()));
        keys.addAll(tokenIndexKeys(generation, authorization));
        store.eval(REMOVE, keys, List.of());
    }

    OAuth2Authorization findById(long generation, String id) {
        byte[] value = store.get(authorizationKey(generation, id));
        return value == null ? null : serializer.deserialize(value);
    }

    OAuth2Authorization findByToken(long generation, String token, OAuth2TokenType tokenType) {
        byte[] id = store.get(tokenIndexKey(generation, token));
        if (id == null) {
            return null;
        }
        OAuth2Authorization authorization = findById(generation, new String(id, StandardCharsets.UTF_8));
        if (authorization == null) {
            return null;
        }
        OAuth2Authorization.Token<?> found = authorization.getToken(token);
        return found != null && matches(found.getToken(), tokenType) ? authorization : null;
    }

    private Set<String> tokenIndexKeys(long generation, OAuth2Authorization authorization) {
        Set<String> keys = new LinkedHashSet<>();
        for (Class<? extends OAuth2Token> type : TOKEN_TYPES) {
            OAuth2Authorization.Token<?> token = authorization.getToken(type);
            if (token != null) {
                keys.add(tokenIndexKey(generation, token.getToken().getTokenValue()));
            }
        }
        return keys;
    }

    private boolean matches(OAuth2Token token, OAuth2TokenType requestedType) {
        if (requestedType == null) {
            return true;
        }
        if (OAuth2TokenType.ACCESS_TOKEN.equals(requestedType)) {
            return token instanceof OAuth2AccessToken;
        }
        if (OAuth2TokenType.REFRESH_TOKEN.equals(requestedType)) {
            return token instanceof OAuth2RefreshToken;
        }
        return switch (requestedType.getValue()) {
            case "code" -> token instanceof OAuth2AuthorizationCode;
            case "id_token" -> token instanceof OidcIdToken;
            case "device_code" -> token instanceof OAuth2DeviceCode;
            case "user_code" -> token instanceof OAuth2UserCode;
            default -> false;
        };
    }

    private String authorizationKey(long generation, String id) {
        return generationPrefix(generation) + "authorization:" + id;
    }

    private String tokenIndexKey(long generation, String token) {
        return generationPrefix(generation) + "token:" + sha256(token);
    }

    private String generationPrefix(long generation) {
        return namespace + ":g" + generation + ":";
    }

    private long expirationMillis(OAuth2Authorization authorization) {
        Instant latest = TOKEN_TYPES
            .stream()
            .map(authorization::getToken)
            .filter(java.util.Objects::nonNull)
            .map(OAuth2Authorization.Token::getToken)
            .map(OAuth2Token::getExpiresAt)
            .filter(java.util.Objects::nonNull)
            .max(Instant::compareTo)
            .orElse(Instant.now().plus(Duration.ofMinutes(10)));
        Duration remaining = Duration.between(Instant.now(), latest.plus(Duration.ofMinutes(1)));
        return Math.max(Duration.ofMinutes(10).toMillis(), remaining.toMillis());
    }

    private static String sha256(String value) {
        try {
            return HexFormat
                .of()
                .formatHex(MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException(impossible);
        }
    }
}
