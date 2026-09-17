package tech.calcifer.auth.state;

import java.util.Objects;


public record StateRoute(
    Owner owner,
    long generation,
    String isolationEpoch
) {

    public enum Owner {REDIS, LOCAL, UNAVAILABLE}

    public StateRoute {
        Objects.requireNonNull(owner, "owner");
        if (owner == Owner.REDIS && generation < 1) {
            throw new IllegalArgumentException("Redis routes require a positive generation");
        }
        if (owner == Owner.LOCAL && (isolationEpoch == null || isolationEpoch.isBlank())) {
            throw new IllegalArgumentException("Local routes require an isolation epoch");
        }
    }

    public static StateRoute redis(long generation) {
        return new StateRoute(Owner.REDIS, generation, null);
    }

    public static StateRoute local(String epoch) {
        return new StateRoute(Owner.LOCAL, 0, epoch);
    }

    public static StateRoute unavailable() {
        return new StateRoute(Owner.UNAVAILABLE, 0, null);
    }

    String identifierPrefix() {
        return owner == Owner.REDIS ? "r." + generation + "."
            : owner == Owner.LOCAL ? "l." + isolationEpoch + "." : "u.";
    }
}
