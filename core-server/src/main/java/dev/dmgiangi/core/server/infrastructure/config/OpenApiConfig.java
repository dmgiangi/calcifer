package dev.dmgiangi.core.server.infrastructure.config;

import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.info.Contact;
import io.swagger.v3.oas.models.info.Info;
import io.swagger.v3.oas.models.info.License;
import io.swagger.v3.oas.models.servers.Server;
import io.swagger.v3.oas.models.tags.Tag;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.util.List;

/**
 * OpenAPI/Swagger configuration for Calcifer Core Server.
 * Provides API documentation with metadata, tags, and server information.
 */
@Configuration
public class OpenApiConfig {

    @Bean
    public OpenAPI calciferOpenAPI() {
        return new OpenAPI()
                .info(apiInfo())
                .servers(List.of(
                        new Server().url("/").description("Default Server")
                ))
                .tags(List.of(
                        new Tag()
                                .name("Device Intent")
                                .description("Operations for managing device user intents and twin snapshots"),
                        new Tag()
                                .name("Device Override")
                                .description("Operations for managing device-level overrides"),
                        new Tag()
                                .name("FunctionalSystem")
                                .description("Operations for managing FunctionalSystem aggregates"),
                        new Tag()
                                .name("System Override")
                                .description("Operations for managing system-level overrides")
                ));
    }

    private Info apiInfo() {
        return new Info()
                .title("Calcifer Core Server API")
                .description("""
                        IoT Digital Twin management system with safety rules and override management.
                        
                        ## Key Features
                        - **Three-State Digital Twin**: Tracks Intent, Reported, and Desired states
                        - **FunctionalSystem Aggregates**: Group devices into logical systems
                        - **Safety Rules Engine**: Hardcoded + configurable rules with SpEL
                        - **Override Management**: Categorized overrides (EMERGENCY > MAINTENANCE > SCHEDULED > MANUAL)
                        - **Event-Driven Reconciliation**: Immediate command dispatch with debounce
                        
                        ## Device Types
                        - **RELAY**: On/Off switching (Boolean value)
                        - **FAN**: Variable speed control (Integer 0-4)
                        - **TEMPERATURE_SENSOR**: Read-only sensor (no intents allowed)
                        
                        ## Override Categories (by precedence)
                        1. **EMERGENCY**: Critical safety situations (highest priority)
                        2. **MAINTENANCE**: Scheduled maintenance windows
                        3. **SCHEDULED**: Time-based automation
                        4. **MANUAL**: User-initiated temporary changes (lowest priority)
                        """)
                .version("1.0.0")
                .contact(new Contact()
                        .name("Calcifer Team")
                        .email("calcifer@example.com"))
                .license(new License()
                        .name("MIT")
                        .url("https://opensource.org/licenses/MIT"));
    }
}

