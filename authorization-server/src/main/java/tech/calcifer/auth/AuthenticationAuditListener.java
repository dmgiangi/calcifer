package tech.calcifer.auth;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.event.EventListener;
import org.springframework.security.authentication.event.AbstractAuthenticationFailureEvent;
import org.springframework.security.authentication.event.AuthenticationSuccessEvent;
import org.springframework.stereotype.Component;

@Component
class AuthenticationAuditListener {
  private static final Logger log = LoggerFactory.getLogger(AuthenticationAuditListener.class);

  @EventListener
  void successfulAuthentication(AuthenticationSuccessEvent event) {
    log.info("authentication_success mechanism={}", event.getAuthentication().getClass().getSimpleName());
  }

  @EventListener
  void failedAuthentication(AbstractAuthenticationFailureEvent event) {
    log.info("authentication_failure mechanism={} reason={}",
        event.getAuthentication().getClass().getSimpleName(), event.getException().getClass().getSimpleName());
  }
}
