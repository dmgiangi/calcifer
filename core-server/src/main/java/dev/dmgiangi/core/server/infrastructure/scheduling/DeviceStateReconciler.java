package dev.dmgiangi.core.server.infrastructure.scheduling;

import dev.dmgiangi.core.server.domain.port.DeviceStateRepository;
import dev.dmgiangi.core.server.infrastructure.messaging.outbound.event.DeviceCommandEvent;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;


@Slf4j
@Component
@RequiredArgsConstructor
public class DeviceStateReconciler {

    private final DeviceStateRepository repository;
    private final ApplicationEventPublisher eventPublisher;

    @Scheduled(fixedRateString = "${app.iot.polling-interval-ms:5000}")
    public void reconcileStates() {
        final var actuators = repository.findAllActiveOutputDevices();

        for (final var device : actuators) {
            try {
                final var deviceCommandEvent = new DeviceCommandEvent(device.id(), device.type(), device.value());
                eventPublisher.publishEvent(deviceCommandEvent);
            } catch (Exception e) {
                log.error("Reconciliation failed for device {}", device.id(), e);
            }
        }
    }
}