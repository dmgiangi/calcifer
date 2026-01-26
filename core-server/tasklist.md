# Calcifer Digital Twin - Undone Task List

> Generated: 2026-01-25
> Purpose: Resume Three-State Digital Twin implementation in a future session

## Phase 2.5: Integration Testing (IN_PROGRESS)

### 2.5.1 Write Redis Hash operations integration tests
- **Status:** IN_PROGRESS (user will complete manually with Testcontainers)
- **File:** `core-server/src/test/java/.../RedisDeviceStateRepositoryAdapterIntegrationTest.java`
- **Note:** 16 tests written, 10 failing due to serialization issue. User setting up Testcontainers manually.

---

## Phase 3: Integration (NOT_STARTED)

### 3.1 Create MQTT Inbound Flow for Actuator Feedback
- [ ] Create `ActuatorFeedbackAmqpFlowConfig.java` - Define queue, exchange binding, IntegrationFlow
- [ ] Define feedback routing key pattern (`*.*.digital_output.*.status` or similar)
- [ ] Create `AmqpToActuatorFeedbackTransformer.java` - Parse MQTT headers/payload

### 3.2 Create Feedback Domain Model
- [ ] Create `ActuatorFeedback.java` record (DeviceId, DeviceType, String rawValue, Instant receivedAt)
- [ ] Create `ActuatorFeedbackReceivedEvent.java` (extends ApplicationEvent)

### 3.3 Create Feedback Processing Service
- [ ] Create `ActuatorFeedbackProcessor.java` with @EventListener for ActuatorFeedbackReceivedEvent
- [ ] Implement feedback → ReportedDeviceState mapping (rawValue → Boolean/StepRelayState)
- [ ] Save ReportedDeviceState and publish ReportedStateChangedEvent

### 3.4 Wire DeviceLogicService to Events
- [ ] Implement `recalculateDesiredState(DeviceId)` - load snapshot, calculate, save, publish event
- [ ] Implement `calculateDesired(DeviceTwinSnapshot)` - initial passthrough logic

### 3.5 Update API Controller for UserIntent
- [ ] Create `DeviceIntentController.java` (@RestController /api/devices)
- [ ] Implement `POST /api/devices/{controllerId}/{componentId}/intent`
- [ ] Implement `GET /api/devices/{controllerId}/{componentId}/twin`
- [ ] Create `IntentRequest.java` DTO

### 3.6 Phase 3 Integration Testing
- [ ] Write MQTT feedback flow integration tests
- [ ] Write ActuatorFeedbackProcessor tests (RELAY 0/1→Boolean, STEP_RELAY→StepRelayState)
- [ ] Write DeviceLogicService event handling tests
- [ ] Write API integration tests (MockMvc)
- [ ] Write end-to-end flow test (Intent → DeviceLogicService → Desired → Reconciler → Command)

---

## Phase 4: Logic Evolution (NOT_STARTED)

### 4.1 Handle Edge Case: Device Boot / Cold Start
- [ ] Implement cold start logic in `calculateDesired()` - when reported=null or !isKnown, use Intent
- [ ] Add logging for cold start scenarios

### 4.2 Handle Edge Case: Race Conditions
- [ ] Add timestamp comparison (Intent.requestedAt > Reported.reportedAt → prioritize Intent)
- [ ] Evaluate Redis WATCH for optimistic locking (document decision)

### 4.3 Handle Edge Case: StepRelay Partial Feedback
- [ ] Define StepRelay feedback strategy (single logical vs dual physical values)
- [ ] Implement StepRelayState reconstruction if firmware reports dual values
- [ ] Add StepRelay validation in ReportedDeviceState

### 4.4 Implement Convergence Detection
- [ ] Create `isConverged(DeviceTwinSnapshot)` method (Reported.value == Desired.value)
- [ ] Add convergence status to GET /twin response
- [ ] Add convergence metrics/logging

### 4.5 Phase 4 Unit Testing
- [ ] Write cold start logic tests
- [ ] Write race condition handling tests
- [ ] Write StepRelay partial feedback tests
- [ ] Write convergence detection tests

---

## Phase 5: Cleanup & Documentation (NOT_STARTED)

### 5.1 Deprecate Legacy Methods
- [ ] Mark `saveDesiredState()` as @Deprecated (keep for Reconciler internal use)
- [ ] Update or remove `TestController.java`
- [ ] Audit codebase for direct DesiredState writes

### 5.2 Clean Up Unused Code
- [ ] Remove RedisMigrationRunner after migration (N/A - was cancelled)
- [ ] Remove backward compatibility fallbacks (N/A - was cancelled)

### 5.3 Final Integration Testing
- [ ] Write full end-to-end integration test
- [ ] Test Reconciler with new 3-state model
- [ ] Perform load testing

### 5.4 Address Open Questions
- [x] ~~Confirm MQTT feedback topic pattern with firmware team~~ (DONE)
- [ ] Decide on historical state persistence (TimeSeries DB?)
- [ ] Define safety rules for DeviceLogicService (rule engine?)
- [ ] Define device offline detection strategy

---

## Standalone Follow-up Task

### Clarify STEP_RELAY to Firmware Handler Mapping
- [ ] Does STEP_RELAY map to single PWM output (levels→duty cycles) or digital_output + pwm together?
- **Impact:** Affects ReportedDeviceState parsing, not a blocker for Phase 3

---

## Completed Phases (Reference)

- ✅ **Phase 1: Foundation** - Domain models, repository interface, events, DeviceLogicService
- ✅ **Phase 2.1-2.2:** Redis Hash configuration and repository methods implemented
- ❌ **Phase 2.3-2.4:** Cancelled (no legacy data, Redis will be flushed)

