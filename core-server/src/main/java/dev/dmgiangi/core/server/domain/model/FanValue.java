package dev.dmgiangi.core.server.domain.model;

import org.springframework.util.Assert;

/**
 * Represents a fan speed value for AC dimmer fan control.
 * <p>
 * Value range: 0-100 where:
 * <ul>
 *   <li>0 = OFF (relay disabled, dimmer at 0%)</li>
 *   <li>1-100 = ON (relay enabled, dimmer level mapped from minPwm to 100%)</li>
 * </ul>
 * <p>
 * The firmware FanHandler automatically controls the relay based on this value:
 * when speed is 0, the relay is turned OFF; when speed is 1-100, the relay is ON.
 *
 * @param speed the fan speed value (0-100)
 */
public record FanValue(int speed) implements DeviceValue {

    public FanValue {
        Assert.isTrue(speed >= 0 && speed <= 100, "Fan speed must be between 0 and 100");
    }
}

