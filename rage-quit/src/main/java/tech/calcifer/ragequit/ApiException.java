package tech.calcifer.ragequit;

import org.springframework.http.HttpStatus;

public final class ApiException extends RuntimeException {
    private final HttpStatus status;
    private final String code;

    public ApiException(HttpStatus status, String code) {
        super(code);
        this.status = status;
        this.code = code;
    }

    public HttpStatus status() { return status; }
    public String code() { return code; }
    public static ApiException invalid() { return new ApiException(HttpStatus.BAD_REQUEST, "invalid_request"); }
    public static ApiException conflict() { return new ApiException(HttpStatus.CONFLICT, "conflict"); }
    public static ApiException missing() { return new ApiException(HttpStatus.NOT_FOUND, "not_found"); }
    public static ApiException unavailable() { return new ApiException(HttpStatus.SERVICE_UNAVAILABLE, "storage_unavailable"); }
}
