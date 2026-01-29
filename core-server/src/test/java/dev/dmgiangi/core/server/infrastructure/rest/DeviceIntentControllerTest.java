package dev.dmgiangi.core.server.infrastructure.rest;

import dev.dmgiangi.core.server.application.override.OverrideApplicationService;
import dev.dmgiangi.core.server.domain.model.*;
import dev.dmgiangi.core.server.domain.model.event.UserIntentChangedEvent;
import dev.dmgiangi.core.server.domain.port.DeviceStateRepository;
import dev.dmgiangi.core.server.domain.port.FunctionalSystemRepository.FunctionalSystemData;
import dev.dmgiangi.core.server.domain.service.DeviceSystemMappingService;
import dev.dmgiangi.core.server.infrastructure.rest.dto.IntentRequest;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.event.EventListener;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import tools.jackson.databind.ObjectMapper;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(DeviceIntentController.class)
@Import(DeviceIntentControllerTest.EventCaptorConfig.class)
@DisplayName("DeviceIntentController")
class DeviceIntentControllerTest {

    private static final String CONTROLLER_ID = "controller1";
    private static final String COMPONENT_ID = "relay1";

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @MockitoBean
    private DeviceStateRepository repository;

    @MockitoBean
    private OverrideApplicationService overrideApplicationService;

    @MockitoBean
    private DeviceSystemMappingService deviceSystemMappingService;

    @Autowired
    private TestEventCaptor eventCaptor;

    /**
     * Test configuration that provides an event listener to capture published events.
     * This is necessary because ApplicationEventPublisher is a core Spring infrastructure
     * bean that cannot be easily mocked with @MockitoBean in @WebMvcTest context.
     */
    @TestConfiguration
    static class EventCaptorConfig {
        @Bean
        TestEventCaptor testEventCaptor() {
            return new TestEventCaptor();
        }
    }

    /**
     * Helper class to capture Spring application events during tests.
     */
    static class TestEventCaptor {
        private final List<UserIntentChangedEvent> userIntentChangedEvents =
                Collections.synchronizedList(new ArrayList<>());

        @EventListener
        void onUserIntentChanged(UserIntentChangedEvent event) {
            userIntentChangedEvents.add(event);
        }

        List<UserIntentChangedEvent> getUserIntentChangedEvents() {
            return new ArrayList<>(userIntentChangedEvents);
        }

        void clear() {
            userIntentChangedEvents.clear();
        }
    }

    @BeforeEach
    void setUp() {
        eventCaptor.clear();
    }

    @Nested
    @DisplayName("POST /api/devices/{controllerId}/{componentId}/intent")
    class SubmitIntentTests {

        @Test
        @DisplayName("should save RELAY intent and return standalone response when device not in system")
        void shouldSaveRelayIntentWithBoolean() throws Exception {
            final var request = new IntentRequest(DeviceType.RELAY, true);
            final var deviceId = new DeviceId(CONTROLLER_ID, COMPONENT_ID);

            // Device is standalone (not in any system)
            when(deviceSystemMappingService.findSystemByDevice(deviceId)).thenReturn(Optional.empty());

            mockMvc.perform(post("/api/devices/{controllerId}/{componentId}/intent", CONTROLLER_ID, COMPONENT_ID)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(request)))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.deviceId").value(CONTROLLER_ID + ":" + COMPONENT_ID))
                    .andExpect(jsonPath("$.systemId").doesNotExist())
                    .andExpect(jsonPath("$.type").value("RELAY"))
                    .andExpect(jsonPath("$.value").value(true))
                    .andExpect(jsonPath("$.message").value("Intent saved. Device is standalone - direct passthrough to desired state."));

            final var intentArgumentCaptor = ArgumentCaptor.forClass(UserIntent.class);
            verify(repository).saveUserIntent(intentArgumentCaptor.capture());

            final var savedIntent = intentArgumentCaptor.getValue();
            assertThat(savedIntent.id()).isEqualTo(deviceId);
            assertThat(savedIntent.type()).isEqualTo(DeviceType.RELAY);
            assertThat(savedIntent.value()).isEqualTo(new RelayValue(true));

            // Verify event was published using TestEventCaptor
            final var publishedEvents = eventCaptor.getUserIntentChangedEvents();
            assertThat(publishedEvents).hasSize(1);
            assertThat(publishedEvents.getFirst().getIntent()).isEqualTo(savedIntent);
        }

        @Test
        @DisplayName("should save FAN intent with integer value")
        void shouldSaveFanIntentWithInteger() throws Exception {
            final var request = new IntentRequest(DeviceType.FAN, 3);
            final var deviceId = new DeviceId(CONTROLLER_ID, "fan1");

            when(deviceSystemMappingService.findSystemByDevice(deviceId)).thenReturn(Optional.empty());

            mockMvc.perform(post("/api/devices/{controllerId}/{componentId}/intent", CONTROLLER_ID, "fan1")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(request)))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.type").value("FAN"))
                    .andExpect(jsonPath("$.value").value(3));

            final var intentCaptor = ArgumentCaptor.forClass(UserIntent.class);
            verify(repository).saveUserIntent(intentCaptor.capture());

            final var savedIntent = intentCaptor.getValue();
            assertThat(savedIntent.type()).isEqualTo(DeviceType.FAN);
            assertThat(savedIntent.value()).isEqualTo(new FanValue(3));
        }

        @Test
        @DisplayName("should save RELAY intent with numeric value (1 = true)")
        void shouldSaveRelayIntentWithNumericValue() throws Exception {
            final var request = new IntentRequest(DeviceType.RELAY, 1);
            final var deviceId = new DeviceId(CONTROLLER_ID, COMPONENT_ID);

            when(deviceSystemMappingService.findSystemByDevice(deviceId)).thenReturn(Optional.empty());

            mockMvc.perform(post("/api/devices/{controllerId}/{componentId}/intent", CONTROLLER_ID, COMPONENT_ID)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(request)))
                    .andExpect(status().isOk());

            final var intentCaptor = ArgumentCaptor.forClass(UserIntent.class);
            verify(repository).saveUserIntent(intentCaptor.capture());
            assertThat(intentCaptor.getValue().value()).isEqualTo(new RelayValue(true));
        }

        @Test
        @DisplayName("should save RELAY intent with numeric value (0 = false)")
        void shouldSaveRelayIntentWithNumericValueZero() throws Exception {
            final var request = new IntentRequest(DeviceType.RELAY, 0);
            final var deviceId = new DeviceId(CONTROLLER_ID, COMPONENT_ID);

            when(deviceSystemMappingService.findSystemByDevice(deviceId)).thenReturn(Optional.empty());

            mockMvc.perform(post("/api/devices/{controllerId}/{componentId}/intent", CONTROLLER_ID, COMPONENT_ID)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(request)))
                    .andExpect(status().isOk());

            final var intentCaptor = ArgumentCaptor.forClass(UserIntent.class);
            verify(repository).saveUserIntent(intentCaptor.capture());
            assertThat(intentCaptor.getValue().value()).isEqualTo(new RelayValue(false));
        }

        @Test
        @DisplayName("should return system info when device belongs to a FunctionalSystem")
        void shouldReturnSystemInfoWhenDeviceInSystem() throws Exception {
            final var request = new IntentRequest(DeviceType.RELAY, true);
            final var deviceId = new DeviceId(CONTROLLER_ID, COMPONENT_ID);

            // Device belongs to a FunctionalSystem
            final var now = java.time.Instant.now();
            final var systemData = new FunctionalSystemData(
                    "system-123",
                    "HVAC",
                    "Kitchen HVAC",
                    java.util.Map.of(),  // configuration
                    java.util.Set.of(CONTROLLER_ID + ":" + COMPONENT_ID),  // deviceIds
                    java.util.Map.of(),  // failSafeDefaults
                    now,  // createdAt
                    now,  // updatedAt
                    "admin",  // createdBy
                    1L  // version
            );
            when(deviceSystemMappingService.findSystemByDevice(deviceId)).thenReturn(Optional.of(systemData));

            mockMvc.perform(post("/api/devices/{controllerId}/{componentId}/intent", CONTROLLER_ID, COMPONENT_ID)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(request)))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.deviceId").value(CONTROLLER_ID + ":" + COMPONENT_ID))
                    .andExpect(jsonPath("$.systemId").value("system-123"))
                    .andExpect(jsonPath("$.systemName").value("Kitchen HVAC"))
                    .andExpect(jsonPath("$.type").value("RELAY"))
                    .andExpect(jsonPath("$.value").value(true))
                    .andExpect(jsonPath("$.message").value("Intent saved. Device belongs to system 'Kitchen HVAC' - will be processed through safety rules."));

            verify(repository).saveUserIntent(any(UserIntent.class));
        }
    }

    @Nested
    @DisplayName("GET /api/devices/{controllerId}/{componentId}/twin")
    class GetTwinSnapshotTests {

        @Test
        @DisplayName("should return twin snapshot when found")
        void shouldReturnTwinSnapshotWhenFound() throws Exception {
            final var deviceId = new DeviceId(CONTROLLER_ID, COMPONENT_ID);
            final var intent = UserIntent.now(deviceId, DeviceType.RELAY, new RelayValue(true));
            final var snapshot = new DeviceTwinSnapshot(deviceId, DeviceType.RELAY, intent, null, null);

            when(repository.findTwinSnapshot(deviceId)).thenReturn(Optional.of(snapshot));

            mockMvc.perform(get("/api/devices/{controllerId}/{componentId}/twin", CONTROLLER_ID, COMPONENT_ID))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.id.controllerId").value(CONTROLLER_ID))
                    .andExpect(jsonPath("$.id.componentId").value(COMPONENT_ID))
                    .andExpect(jsonPath("$.type").value("RELAY"))
                    .andExpect(jsonPath("$.intent").exists())
                    .andExpect(jsonPath("$.intent.type").value("RELAY"));
        }

        @Test
        @DisplayName("should return 404 when twin snapshot not found")
        void shouldReturn404WhenNotFound() throws Exception {
            final var deviceId = new DeviceId(CONTROLLER_ID, COMPONENT_ID);
            when(repository.findTwinSnapshot(deviceId)).thenReturn(Optional.empty());

            mockMvc.perform(get("/api/devices/{controllerId}/{componentId}/twin", CONTROLLER_ID, COMPONENT_ID))
                    .andExpect(status().isNotFound());
        }
    }
}

