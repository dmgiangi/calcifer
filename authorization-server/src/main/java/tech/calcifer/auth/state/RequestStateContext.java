package tech.calcifer.auth.state;

final class RequestStateContext {

    private static final ThreadLocal<StateRoute> ROUTE = new ThreadLocal<>();

    private RequestStateContext() {
    }

    static void bind(StateRoute route) {
        if (ROUTE.get() != null) {
            throw new IllegalStateException("A state route is already bound to this request");
        }
        ROUTE.set(route);
    }

    static StateRoute requireRoute() {
        StateRoute route = ROUTE.get();
        if (route == null) {
            throw new StateUnavailableException("No request state route is available");
        }
        return route;
    }

    static void clear() {
        ROUTE.remove();
    }
}
