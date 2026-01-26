# Domain Model

This document describes the domain model of the Core Server ("Calcifer"), defining the entities, value objects, business rules, and interaction patterns used to manage IoT devices.

## 1. Core Concepts

The domain is structured around the concept of bidirectional IoT device management, clearly distinguishing between **Input** flows (data acquisition) and **Output** flows (command actuation).

### Device Identity (`DeviceId`)
Each device in the system is uniquely identified by a composite key that reflects the physical hardware topology.

* **Controller ID:** The identifier of the physical microcontroller (e.g., `esp32-kitchen`).
* **Component ID:** The identifier of the sensor or actuator connected to that controller (e.g., `main-light`, `ds18b20`).

> **Rule:** A device does not exist without both parts. The string representation is `controllerId:componentId`.

### Classification and Capability (`DeviceType` & `DeviceCapability`)
Devices are classified by type, and each type possesses an intrinsic "capability" that dictates how the system interacts with it.

| Device Type | Capability | Description | Management Flow |
| :--- | :--- | :--- | :--- |
| `TEMPERATURE_SENSOR` | **INPUT** | Produces data (readings). | Event-Driven (Reactive) |
| `RELAY` | **OUTPUT** | Performs actions (ON/OFF). | State Reconciliation |
| `STEP_RELAY` | **OUTPUT** | Performs complex actions (Levels). | State Reconciliation |

---

## 2. Subdomain: Input (Sensors)

Manages the passive reception of data from the outside world. The system reacts to the received data.

### Entity: `Temperature`
Represents a single temperature reading at a specific moment. It is an immutable object (Record).

* **Attributes:**
    * `client`: The MQTT client that sent the data.
    * `type`: The hardware type (`ds18b20`, `thermocouple`).
    * `sensorName`: Identifier of the specific sensor.
    * `value`: The numeric temperature value.
    * `isError`: Flag indicating if the reading failed on the hardware side.

### Event: `TemperatureReceivedEvent`
When data is received and validated by the infrastructure, this domain event is emitted. The domain does not persist history directly in the hot path but notifies interested parties.

---

## 3. Subdomain: Output (Actuators)

Manages the active control of devices. Unlike sensors, here the domain is authoritative: it defines how the outside world *should* be.

### Pattern: Desired State vs Command
The system **does not send direct commands** ("Turn on now"). Instead, the system **persists a desired state** ("You must be on").
A separate process (Reconciler) is responsible for aligning reality with the desired state.

### Entity: `DesiredDeviceState`
Represents the target configuration for an actuator.

* **Value Polymorphism:** The `value` field is generic (`Object`) but strictly validated in the constructor based on `DeviceType`:
    * If `RELAY` -> Requires `Boolean` (True=ON, False=OFF).
    * If `STEP_RELAY` -> Requires `StepRelayState` (Enum).

### Value Object: `StepRelayState`
Models the business logic for multi-state relays (e.g., fans or dimmable lights with steps).
Encapsulates the mapping between the "logical level" and the physical signals sent to the microcontroller.

| Logical State | Level | PWM Value (0-255) | Digital Value (0/1) |
| :--- | :--- | :--- | :--- |
| `OFF` | 0 | 0 | 0 |
| `LEVEL_1` | 1 | 64 | 1 |
| `LEVEL_2` | 2 | 128 | 1 |
| `LEVEL_3` | 3 | 192 | 1 |
| `FULL_POWER` | 4 | 255 | 1 |

---

## 4. Ports & Adapters (Interfaces)

The domain defines the ports necessary to communicate with the infrastructure, maintaining independence from technology (Redis, RabbitMQ, etc.).

### Repository: `DeviceStateRepository`
Interface for persisting the desired state.

* `saveDesiredState(DesiredDeviceState state)`: Saves or updates the user's intention.
* `findAllActiveOutputDevices()`: Specific method for the reconciliation pattern. Must return only `OUTPUT` type devices that require active control.

---

## 5. Operational Flows

### Reconciliation Flow (Outbound)
The system ensures that devices maintain the desired state (*Self-Healing* Pattern).

1.  **Trigger:** A scheduled process (Infrastructure) queries the domain via `findAllActiveOutputDevices`.
2.  **Command Generation:** For each desired state, an internal event is generated.
3.  **Translation:** The infrastructure transforms the domain event into protocol-specific messages (MQTT).
    * *StepRelay Example:* A `LEVEL_1` state is "exploded" into two physical commands: `Digital=1` (Enable) and `PWM=64` (Power).

### Acquisition Flow (Inbound)
1.  **Ingress:** AMQP/MQTT message received on specific topics.
2.  **Transformation:** Parsing of the payload and headers to instantiate the `Temperature` object.
3.  **Publication:** Emission of `TemperatureReceivedEvent` to allow other components (logic, UI, history) to react.
