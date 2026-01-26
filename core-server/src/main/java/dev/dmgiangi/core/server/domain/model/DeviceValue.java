package dev.dmgiangi.core.server.domain.model;

import com.fasterxml.jackson.annotation.JsonSubTypes;
import com.fasterxml.jackson.annotation.JsonTypeInfo;

/**
 * Sealed interface representing a device value.
 * Jackson annotations ensure type information is preserved during serialization/deserialization.
 */
@JsonTypeInfo(use = JsonTypeInfo.Id.CLASS, include = JsonTypeInfo.As.PROPERTY, property = "@class")
@JsonSubTypes({
    @JsonSubTypes.Type(value = RelayValue.class),
    @JsonSubTypes.Type(value = FanValue.class)
})
public sealed interface DeviceValue permits RelayValue, FanValue {
}

