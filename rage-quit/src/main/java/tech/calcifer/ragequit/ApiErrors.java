package tech.calcifer.ragequit;

import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.security.oauth2.core.OAuth2AuthenticationException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;
import org.springframework.web.bind.MissingServletRequestParameterException;

@RestControllerAdvice
public class ApiErrors {
    public record Error(String code) { }

    @ExceptionHandler(ApiException.class)
    ResponseEntity<Error> application(ApiException error) {
        return ResponseEntity.status(error.status()).body(new Error(error.code()));
    }

    @ExceptionHandler({MethodArgumentNotValidException.class, HttpMessageNotReadableException.class,
            MethodArgumentTypeMismatchException.class, MissingServletRequestParameterException.class})
    ResponseEntity<Error> invalid(Exception ignored) {
        return ResponseEntity.badRequest().body(new Error("invalid_request"));
    }

    @ExceptionHandler(OAuth2AuthenticationException.class)
    ResponseEntity<Error> identity() {
        return ResponseEntity.status(HttpStatus.FORBIDDEN).body(new Error("access_denied"));
    }
}
