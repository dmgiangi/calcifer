package dev.dmgiangi.core.server.domain.port;

import dev.dmgiangi.core.server.domain.model.DesiredDeviceState;
import java.util.List;


public interface DeviceStateRepository {

    void saveDesiredState(DesiredDeviceState state);

    List<DesiredDeviceState> findAllActiveOutputDevices();
}