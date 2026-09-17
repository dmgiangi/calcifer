package tech.calcifer.auth.state;

public final class StateUnavailableException extends RuntimeException {

    public StateUnavailableException(String message) {
        super(message);
    }

    public StateUnavailableException(String message, Throwable cause) {
        super(message, cause);
    }
}
