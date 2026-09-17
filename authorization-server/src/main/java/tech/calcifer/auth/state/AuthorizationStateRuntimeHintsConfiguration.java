package tech.calcifer.auth.state;

import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.ImportRuntimeHints;


@Configuration(proxyBeanMethods = false)
@ImportRuntimeHints(AuthorizationStateRuntimeHints.class)
class AuthorizationStateRuntimeHintsConfiguration {}