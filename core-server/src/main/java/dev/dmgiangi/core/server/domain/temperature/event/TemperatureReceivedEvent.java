package dev.dmgiangi.core.server.domain.temperature.event;

import dev.dmgiangi.core.server.domain.temperature.Temperature;
import lombok.Getter;
import org.springframework.context.ApplicationEvent;


public class TemperatureReceivedEvent extends ApplicationEvent {

    @Getter
    private final Temperature temperature;

    public TemperatureReceivedEvent(Object source, Temperature temperature) {
        super(source);
        this.temperature = temperature;
    }
}