package tech.calcifer.ragequit;

import jakarta.validation.Valid;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;

import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.ZoneOffset;

public final class TrackingDtos {
    public static final ZoneId ROME = ZoneId.of("Europe/Rome");
    private TrackingDtos() { }
    public enum EventType { SMOKED, RESISTED }

    public record TimeInput(Boolean now, Instant instant, LocalDateTime localDateTime, ZoneOffset offset) {
        public Instant resolve(Instant serverNow, LocalDate startDate) {
            int choices = (Boolean.TRUE.equals(now) ? 1 : 0) + (instant == null ? 0 : 1) + (localDateTime == null ? 0 : 1);
            if (choices != 1 || Boolean.FALSE.equals(now) || (offset != null && localDateTime == null)) {
                throw ApiException.invalid();
            }
            Instant effective = serverNow;
            if (instant != null) {
                effective = instant;
            } else if (localDateTime != null) {
                // Never let atZone silently normalize a gap or select one side of an overlap.
                if (offset == null || !ROME.getRules().getValidOffsets(localDateTime).contains(offset)) {
                    throw ApiException.invalid();
                }
                effective = localDateTime.toInstant(offset);
            }
            if (effective.isAfter(serverNow) || effective.isBefore(startDate.atStartOfDay(ROME).toInstant())) {
                throw ApiException.invalid();
            }
            return effective;
        }
    }

    public record CreateEvent(@NotNull EventType type,
                              @NotBlank @Pattern(regexp = "[A-Za-z0-9_-]{1,128}") String idempotencyKey,
                              @NotNull @Valid TimeInput time) {
        String originalPayload() {
            // Compare the original request, not the newly resolved timestamp of a retry of "now".
            return type + "|" + time.now() + "|" + time.instant() + "|" + time.localDateTime() + "|" + time.offset();
        }
    }
    public record UpdateEvent(@NotNull @Min(1) Long version, @NotNull EventType type, @NotNull @Valid TimeInput time) { }
    public record Event(String id, EventType type, Instant effectiveAt, Instant createdAt, Instant updatedAt, long version) { }
    public record ZeroDeclaration(LocalDate date, Instant confirmedAt, boolean inProgress) { }
    public record History(java.util.List<Event> events, java.util.List<ZeroDeclaration> zeroDeclarations) { }
    public record Session(String alias, LocalDate startDate, LocalDate today, String csrfToken, String csrfHeader) { }
}
