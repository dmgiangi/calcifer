package dev.dmgiangi.core.server.infrastructure.rest.exception;

import dev.dmgiangi.core.server.domain.exception.DeviceNotFoundException;
import dev.dmgiangi.core.server.domain.exception.OverrideBlockedException;
import dev.dmgiangi.core.server.domain.exception.ResourceNotFoundException;
import dev.dmgiangi.core.server.domain.exception.SafetyRuleViolationException;
import dev.dmgiangi.core.server.infrastructure.exception.InfrastructureUnavailableException;
import jakarta.validation.ConstraintViolationException;
import lombok.extern.slf4j.Slf4j;
import org.slf4j.MDC;
import org.springframework.dao.OptimisticLockingFailureException;
import org.springframework.data.redis.RedisConnectionFailureException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

import java.net.URI;
import java.util.Map;

/**
 * Global exception handler per Phase 0.11.
 * Uses RFC 7807 ProblemDetail for structured error responses.
 */
@Slf4j
@RestControllerAdvice
public class GlobalExceptionHandler {

    private static final String CORRELATION_ID_KEY = "correlationId";
    private static final String ERROR_CODE_KEY = "errorCode";

    @ExceptionHandler(IllegalArgumentException.class)
    public ProblemDetail handleIllegalArgument(final IllegalArgumentException ex) {
        log.warn("Validation error: {}", ex.getMessage());
        final var problem = createProblemDetail(HttpStatus.BAD_REQUEST, "Validation Error", ex.getMessage());
        problem.setProperty(ERROR_CODE_KEY, ErrorCode.VALIDATION_ERROR);
        return problem;
    }

    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ProblemDetail handleMethodArgumentNotValid(final MethodArgumentNotValidException ex) {
        log.warn("Validation error: {}", ex.getMessage());
        final var problem = createProblemDetail(HttpStatus.BAD_REQUEST, "Validation Error", "Request validation failed");

        final var errors = ex.getBindingResult().getFieldErrors().stream()
                .map(error -> Map.of(
                        "field", error.getField(),
                        "message", error.getDefaultMessage() != null ? error.getDefaultMessage() : "Invalid value",
                        "rejectedValue", error.getRejectedValue() != null ? error.getRejectedValue().toString() : "null"
                ))
                .toList();

        problem.setProperty(ERROR_CODE_KEY, ErrorCode.VALIDATION_ERROR);
        problem.setProperty("errors", errors);
        return problem;
    }

    @ExceptionHandler(ConstraintViolationException.class)
    public ProblemDetail handleConstraintViolation(final ConstraintViolationException ex) {
        log.warn("Constraint violation: {}", ex.getMessage());
        final var problem = createProblemDetail(HttpStatus.BAD_REQUEST, "Validation Error", "Path parameter validation failed");

        final var errors = ex.getConstraintViolations().stream()
                .map(violation -> Map.of(
                        "field", extractFieldName(violation.getPropertyPath().toString()),
                        "message", violation.getMessage(),
                        "rejectedValue", violation.getInvalidValue() != null ? violation.getInvalidValue().toString() : "null"
                ))
                .toList();

        problem.setProperty(ERROR_CODE_KEY, ErrorCode.VALIDATION_ERROR);
        problem.setProperty("errors", errors);
        return problem;
    }

    @ExceptionHandler(DeviceNotFoundException.class)
    public ProblemDetail handleDeviceNotFound(final DeviceNotFoundException ex) {
        log.warn("Device not found: {}", ex.getDeviceId());
        final var problem = createProblemDetail(HttpStatus.NOT_FOUND, "Device Not Found", ex.getMessage());
        problem.setProperty(ERROR_CODE_KEY, ErrorCode.NOT_FOUND);
        problem.setProperty("deviceId", ex.getDeviceId().toString());
        return problem;
    }

    @ExceptionHandler(ResourceNotFoundException.class)
    public ProblemDetail handleResourceNotFound(final ResourceNotFoundException ex) {
        log.warn("Resource not found: {} - {}", ex.getResourceType(), ex.getResourceId());
        final var problem = createProblemDetail(HttpStatus.NOT_FOUND, ex.getResourceType() + " Not Found", ex.getMessage());
        problem.setProperty(ERROR_CODE_KEY, ErrorCode.NOT_FOUND);
        problem.setProperty("resourceType", ex.getResourceType());
        problem.setProperty("resourceId", ex.getResourceId());
        return problem;
    }

    @ExceptionHandler(OptimisticLockingFailureException.class)
    public ProblemDetail handleOptimisticLocking(final OptimisticLockingFailureException ex) {
        log.warn("Optimistic locking conflict: {}", ex.getMessage());
        final var problem = createProblemDetail(HttpStatus.CONFLICT, "Conflict", "Resource was modified by another request. Please retry.");
        problem.setProperty(ERROR_CODE_KEY, ErrorCode.CONFLICT);
        return problem;
    }

    @ExceptionHandler(SafetyRuleViolationException.class)
    public ProblemDetail handleSafetyRuleViolation(final SafetyRuleViolationException ex) {
        log.warn("Safety rule violation: {}", ex.getMessage());
        final var problem = createProblemDetail(HttpStatus.UNPROCESSABLE_ENTITY, "Safety Rule Violation", ex.getMessage());
        problem.setProperty(ERROR_CODE_KEY, ErrorCode.SAFETY_BLOCK);
        if (ex.getRuleId() != null) {
            problem.setProperty("ruleId", ex.getRuleId());
        }
        problem.setProperty("ruleName", ex.getRuleName());
        if (ex.getDeviceId() != null) {
            problem.setProperty("deviceId", ex.getDeviceId());
        }
        return problem;
    }

    @ExceptionHandler(OverrideBlockedException.class)
    public ProblemDetail handleOverrideBlocked(final OverrideBlockedException ex) {
        log.warn("Override blocked: {}", ex.getMessage());
        final var problem = createProblemDetail(HttpStatus.UNPROCESSABLE_ENTITY, "Override Blocked", ex.getMessage());
        problem.setProperty(ERROR_CODE_KEY, ErrorCode.SAFETY_BLOCK);
        problem.setProperty("targetId", ex.getTargetId());
        problem.setProperty("requestedCategory", ex.getRequestedCategory());
        problem.setProperty("blockingCategory", ex.getBlockingCategory());
        problem.setProperty("blockingReason", ex.getBlockingReason());
        return problem;
    }

    @ExceptionHandler(RedisConnectionFailureException.class)
    public ProblemDetail handleRedisConnectionFailure(final RedisConnectionFailureException ex) {
        log.error("Redis connection failure: {}", ex.getMessage());
        final var problem = createProblemDetail(HttpStatus.SERVICE_UNAVAILABLE, "Service Unavailable", "State storage temporarily unavailable");
        problem.setProperty(ERROR_CODE_KEY, ErrorCode.REDIS_DOWN);
        return problem;
    }

    @ExceptionHandler(InfrastructureUnavailableException.class)
    public ProblemDetail handleInfrastructureUnavailable(final InfrastructureUnavailableException ex) {
        log.error("Infrastructure unavailable: {} - {}", ex.getComponent(), ex.getMessage());
        final var problem = createProblemDetail(HttpStatus.SERVICE_UNAVAILABLE, "Service Unavailable",
                "Infrastructure temporarily unavailable. Command generation halted for safety.");
        problem.setProperty(ERROR_CODE_KEY, ErrorCode.INFRASTRUCTURE_DOWN);
        problem.setProperty("component", ex.getComponent().name());
        return problem;
    }

    @ExceptionHandler(Exception.class)
    public ProblemDetail handleGenericException(final Exception ex) {
        log.error("Unexpected error: {}", ex.getMessage(), ex);
        final var problem = createProblemDetail(HttpStatus.INTERNAL_SERVER_ERROR, "Internal Server Error", "An unexpected error occurred");
        problem.setProperty(ERROR_CODE_KEY, ErrorCode.INTERNAL_ERROR);
        return problem;
    }

    private ProblemDetail createProblemDetail(final HttpStatus status, final String title, final String detail) {
        final var problem = ProblemDetail.forStatusAndDetail(status, detail);
        problem.setTitle(title);
        problem.setType(URI.create("https://calcifer.dmgiangi.dev/errors/" + status.value()));

        // Include correlationId from MDC if available
        final var correlationId = MDC.get(CORRELATION_ID_KEY);
        if (correlationId != null) {
            problem.setProperty(CORRELATION_ID_KEY, correlationId);
        }

        return problem;
    }

    /**
     * Extracts the field name from a property path (e.g., "submitIntent.controllerId" → "controllerId").
     */
    private String extractFieldName(final String propertyPath) {
        final var lastDot = propertyPath.lastIndexOf('.');
        return lastDot >= 0 ? propertyPath.substring(lastDot + 1) : propertyPath;
    }
}

