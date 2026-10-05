package tech.calcifer.ragequit;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.nio.file.Path;
import java.time.LocalDate;

@ConfigurationProperties("rage-quit")
public record RageQuitProperties(String issuer, String clientId, String clientSecret,
                                 Path database, LocalDate startDate) {
    public RageQuitProperties {
        if (!"https://auth.calcifer.tech".equals(issuer)) {
            throw new IllegalArgumentException("RAGE_QUIT_ISSUER must be the Calcifer HTTPS issuer");
        }
        if (!"rage-quit".equals(clientId)) {
            throw new IllegalArgumentException("RAGE_QUIT_CLIENT_ID must be rage-quit");
        }
        if (clientSecret == null || clientSecret.isBlank()) {
            throw new IllegalArgumentException("RAGE_QUIT_CLIENT_SECRET is required");
        }
        if (isPlaceholderSecret(clientSecret)) {
            throw new IllegalArgumentException("RAGE_QUIT_CLIENT_SECRET must not be a placeholder");
        }
        if (database == null || !database.isAbsolute() || database.getFileName() == null) {
            throw new IllegalArgumentException("RAGE_QUIT_DATABASE must be an absolute file path");
        }
        if (startDate == null) {
            throw new IllegalArgumentException("RAGE_QUIT_START_DATE is required");
        }
    }

    private static boolean isPlaceholderSecret(String secret) {
        String normalized = secret.strip().toLowerCase(java.util.Locale.ROOT)
                .replace('-', '_').replace(' ', '_');
        return normalized.equals("replace") || normalized.startsWith("replace_")
                || normalized.equals("changeme") || normalized.startsWith("change_me")
                || normalized.equals("placeholder") || normalized.startsWith("placeholder_")
                || normalized.equals("example") || normalized.startsWith("example_");
    }

    @Override
    public String toString() {
        return "RageQuitProperties[redacted]";
    }
}
