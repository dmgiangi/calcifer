package tech.calcifer.auth.state;

import java.net.URL;
import java.lang.reflect.Method;
import java.time.Duration;
import java.time.Instant;
import java.util.Collections;
import java.util.Date;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.hibernate.validator.internal.constraintvalidators.bv.AssertTrueValidator;
import org.hibernate.validator.internal.constraintvalidators.bv.NotNullValidator;
import org.hibernate.validator.internal.constraintvalidators.bv.NotBlankValidator;
import org.hibernate.validator.internal.constraintvalidators.bv.PatternValidator;
import org.hibernate.validator.internal.constraintvalidators.bv.number.bound.MaxValidatorForInteger;
import org.hibernate.validator.internal.constraintvalidators.bv.number.bound.MinValidatorForInteger;
import org.hibernate.validator.internal.util.logging.Log_$logger;
import org.hibernate.validator.internal.util.logging.Messages_$bundle;
import org.springframework.aot.hint.ExecutableMode;
import org.springframework.aot.hint.MemberCategory;
import org.springframework.aot.hint.RuntimeHints;
import org.springframework.aot.hint.RuntimeHintsRegistrar;
import org.springframework.aot.hint.TypeReference;
import tech.calcifer.auth.IdentityProperties;
import org.springframework.security.oauth2.client.authentication.OAuth2AuthenticationToken;
import org.springframework.security.oauth2.core.AbstractOAuth2Token;
import org.springframework.security.oauth2.core.AuthorizationGrantType;
import org.springframework.security.oauth2.core.OAuth2AccessToken;
import org.springframework.security.oauth2.core.OAuth2DeviceCode;
import org.springframework.security.oauth2.core.OAuth2Error;
import org.springframework.security.oauth2.core.OAuth2RefreshToken;
import org.springframework.security.oauth2.core.OAuth2UserCode;
import org.springframework.security.oauth2.core.endpoint.OAuth2AuthorizationRequest;
import org.springframework.security.oauth2.core.endpoint.OAuth2AuthorizationResponseType;
import org.springframework.security.oauth2.core.oidc.OidcIdToken;
import org.springframework.security.oauth2.core.oidc.OidcUserInfo;
import org.springframework.security.oauth2.core.oidc.user.DefaultOidcUser;
import org.springframework.security.oauth2.core.user.DefaultOAuth2User;
import org.springframework.security.core.authority.FactorGrantedAuthority;
import org.springframework.security.oauth2.server.authorization.OAuth2Authorization;
import org.springframework.security.oauth2.server.authorization.OAuth2AuthorizationCode;
import org.springframework.session.MapSession;


final class AuthorizationStateRuntimeHints implements RuntimeHintsRegistrar {

    @Override
    public void registerHints(RuntimeHints hints, ClassLoader classLoader) {
        hints.reflection().registerType(Log_$logger.class, MemberCategory.INVOKE_PUBLIC_CONSTRUCTORS);
        hints.reflection().registerType(AssertTrueValidator.class, MemberCategory.INVOKE_PUBLIC_CONSTRUCTORS);
        hints.reflection().registerType(NotBlankValidator.class, MemberCategory.INVOKE_PUBLIC_CONSTRUCTORS);
        hints.reflection().registerType(NotNullValidator.class, MemberCategory.INVOKE_PUBLIC_CONSTRUCTORS);
        hints.reflection().registerType(PatternValidator.class, MemberCategory.INVOKE_PUBLIC_CONSTRUCTORS);
        hints.reflection().registerType(MaxValidatorForInteger.class, MemberCategory.INVOKE_PUBLIC_CONSTRUCTORS);
        hints.reflection().registerType(MinValidatorForInteger.class, MemberCategory.INVOKE_PUBLIC_CONSTRUCTORS);
        hints
            .reflection()
            .registerType(
                Messages_$bundle.class,
                MemberCategory.INVOKE_DECLARED_CONSTRUCTORS,
                MemberCategory.ACCESS_DECLARED_FIELDS
            );
        registerPublicMethods(hints, AuthorizationStateProperties.class);
        hints.reflection().registerType(AuthorizationStateProperties.class, MemberCategory.ACCESS_DECLARED_FIELDS);
        hints
            .reflection()
            .registerType(AuthorizationStateProperties.Redis.class, MemberCategory.ACCESS_DECLARED_FIELDS);
        hints.reflection().registerType(IdentityProperties.Client.class, MemberCategory.ACCESS_DECLARED_FIELDS);
        hints.reflection().registerType(IdentityProperties.LocalLogin.class, MemberCategory.ACCESS_DECLARED_FIELDS);
        registerPublicMethods(hints, IdentityProperties.ClientDefinition.class);
        for (var field : Messages_$bundle.class.getFields()) {
            if (field.getName().equals("INSTANCE")) {
                hints.reflection().registerField(field);
            }
        }
        registerSerialization(hints, TypeReference.of(MapSession.class));
        registerSerialization(hints, TypeReference.of(Instant.class));
        registerSerialization(hints, TypeReference.of(Duration.class));
        registerSerialization(hints, TypeReference.of("java.time.Ser"));
        registerSerialization(hints, TypeReference.of(Boolean.class));
        registerSerialization(hints, TypeReference.of(Integer.class));
        registerSerialization(hints, TypeReference.of(Long.class));
        registerSerialization(hints, TypeReference.of(Double.class));
        registerSerialization(hints, TypeReference.of(Date.class));
        registerSerialization(hints, TypeReference.of(URL.class));
        registerSerialization(hints, TypeReference.of(String[].class));
        registerSerialization(hints, TypeReference.of("java.util.CollSer"));
        registerSerialization(hints, TypeReference.of(HashMap.class));
        registerSerialization(hints, TypeReference.of(HashSet.class));
        registerSerialization(hints, TypeReference.of(LinkedHashMap.class));
        registerSerialization(hints, TypeReference.of(LinkedHashSet.class));
        registerSerialization(hints, TypeReference.of(AuthorizationGrantType.class));
        registerSerialization(hints, TypeReference.of(OAuth2Error.class));
        registerSerialization(hints, TypeReference.of(OAuth2AuthorizationRequest.class));
        registerSerialization(hints, TypeReference.of(OAuth2AuthorizationResponseType.class));
        registerSerialization(hints, TypeReference.of(OAuth2AuthenticationToken.class));
        registerSerialization(hints, TypeReference.of(AbstractOAuth2Token.class));
        registerSerialization(hints, TypeReference.of(OAuth2Authorization.class));
        registerSerialization(hints, TypeReference.of(OAuth2Authorization.Token.class));
        registerSerialization(hints, TypeReference.of(OAuth2AuthorizationCode.class));
        registerSerialization(hints, TypeReference.of(OAuth2AccessToken.class));
        registerSerialization(hints, TypeReference.of(OAuth2AccessToken.TokenType.class));
        registerSerialization(hints, TypeReference.of(OAuth2RefreshToken.class));
        registerSerialization(hints, TypeReference.of(OAuth2DeviceCode.class));
        registerSerialization(hints, TypeReference.of(OAuth2UserCode.class));
        registerSerialization(hints, TypeReference.of(OidcIdToken.class));
        registerSerialization(hints, TypeReference.of(OidcUserInfo.class));
        registerSerialization(hints, TypeReference.of(DefaultOAuth2User.class));
        registerSerialization(hints, TypeReference.of(DefaultOidcUser.class));
        registerSerialization(hints, TypeReference.of(FactorGrantedAuthority.class));
        registerSerialization(hints, TypeReference.of(Collections.unmodifiableMap(new HashMap<>()).getClass()));
        registerSerialization(hints, TypeReference.of(Collections.unmodifiableSet(new HashSet<>()).getClass()));
        registerSerialization(hints, TypeReference.of(List.of().getClass()));
        registerSerialization(hints, TypeReference.of(List.of("value").getClass()));
        registerSerialization(hints, TypeReference.of(Map.of().getClass()));
        registerSerialization(hints, TypeReference.of(Map.of("key", "value").getClass()));
        registerSerialization(hints, TypeReference.of(Set.of().getClass()));
        registerSerialization(hints, TypeReference.of(Set.of("value").getClass()));
    }

    private static void registerPublicMethods(RuntimeHints hints, Class<?> type) {
        for (Method method : type.getMethods()) {
            if (method.getDeclaringClass().equals(type)) {
                hints.reflection().registerMethod(method, ExecutableMode.INVOKE);
            }
        }
    }

    private static void registerSerialization(RuntimeHints hints, TypeReference type) {
        hints.reflection().registerType(type, typeHint -> typeHint.withJavaSerialization(true));
    }
}
