package dev.dmgiangi.core.server;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;


@SpringBootApplication
@ConfigurationPropertiesScan
public class CoreServerApplication {

    static void main(String[] args) {
        SpringApplication.run(CoreServerApplication.class, args);
    }
}
