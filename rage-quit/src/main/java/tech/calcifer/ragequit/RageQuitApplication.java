package tech.calcifer.ragequit;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;
import org.springframework.context.annotation.Bean;

import java.time.Clock;

@SpringBootApplication
@ConfigurationPropertiesScan
public class RageQuitApplication {
    public static void main(String[] args) {
        SpringApplication.run(RageQuitApplication.class, args);
    }

    @Bean
    Clock clock() {
        return Clock.systemUTC();
    }
}