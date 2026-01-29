package dev.dmgiangi.core.server.domain.model;

import org.springframework.util.Assert;

/**
 * Represents a fan speed value for 3-relay discrete fan control.
 * <p>
 * Value range: 0-4 (5 discrete states) where:
 * <ul>
 *   <li>0 = OFF (all relays disabled)</li>
 *   <li>1 = Lowest speed</li>
 *   <li>2 = Medium-low speed</li>
 *   <li>3 = Medium-high speed</li>
 *   <li>4 = Maximum speed</li>
 * </ul>
 * <p>
 * The firmware FanHandler uses 3 relays to achieve 5 discrete speed states.
 * The kickstart feature applies full power briefly when starting from OFF.
 *
 * @param speed the fan speed value (0-4)
 */
public record FanValue(int speed) implements DeviceValue {

    public FanValue {
        Assert.isTrue(speed >= 0 && speed <= 4, "Fan speed must be between 0 and 4");
    }
}

