package dev.dmgiangi.core.server.infrastructure.configuration;

import com.fasterxml.jackson.annotation.JsonTypeInfo;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.redis.connection.RedisConnectionFactory;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.data.redis.serializer.GenericJacksonJsonRedisSerializer;
import org.springframework.data.redis.serializer.StringRedisSerializer;
import tools.jackson.databind.DefaultTyping;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.jsontype.BasicPolymorphicTypeValidator;

@Configuration
public class RedisConfig {

    @Bean
    public RedisTemplate<String, Object> redisTemplate(
            RedisConnectionFactory connectionFactory,
            ObjectMapper objectMapper) {

        // Create a PolymorphicTypeValidator that allows domain package types
        final var ptv = BasicPolymorphicTypeValidator.builder()
                .allowIfBaseType("dev.dmgiangi.core.server.domain")
                .allowIfSubType("dev.dmgiangi.core.server.domain")
                .allowIfBaseType("java.time.Instant")
                .allowIfSubType("java.time.Instant")
                .build();

        // Create a dedicated ObjectMapper for Redis with default typing enabled
        // This ensures type information (@class) is included in serialized JSON
        // Jackson 3.x uses rebuild().build() instead of copy()
        // Using JAVA_LANG_OBJECT to add type info for Object-typed fields (RedisTemplate uses Object)
        // Combined with @JsonTypeInfo annotations on domain classes for comprehensive type handling
        // PROPERTY format embeds @class as a JSON property instead of WRAPPER_ARRAY
        final var redisObjectMapper = objectMapper.rebuild()
                .activateDefaultTyping(ptv, DefaultTyping.JAVA_LANG_OBJECT, JsonTypeInfo.As.PROPERTY)
                .build();

        final var template = new RedisTemplate<String, Object>();
        final var jsonSerializer = new GenericJacksonJsonRedisSerializer(redisObjectMapper);

        template.setConnectionFactory(connectionFactory);
        template.setKeySerializer(new StringRedisSerializer());
        template.setValueSerializer(jsonSerializer);
        template.setHashKeySerializer(new StringRedisSerializer());
        template.setHashValueSerializer(jsonSerializer);

        return template;
    }
}