package dev.dmgiangi.core.server.infrastructure.rest.exception;

/**
 * Error codes for client parsing per Phase 0.11.
 * Used in RFC 7807 ProblemDetail responses.
 */
public enum ErrorCode {
    VALIDATION_ERROR,
    PARSE_ERROR,
    NOT_FOUND,
    CONFLICT,
    SAFETY_BLOCK,
    REDIS_DOWN,
    AMQP_DOWN,
    INFRASTRUCTURE_DOWN,
    INTERNAL_ERROR
}

