package tech.calcifer.ragequit;

import org.springframework.stereotype.Service;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static tech.calcifer.ragequit.TrackingDtos.*;

@Service
public class InsightsService {
    public enum SmokingReport { MISSING, SMOKED, ZERO_CONFIRMED }
    public record Day(LocalDate date, Long penalties, long resistancePoints, SmokingReport smokingReport,
                      boolean inProgress, long recordedCumulativePenalties, Long cumulativePenalties,
                      long cumulativeResistancePoints, long cumulativeMissingPastDays, boolean cumulativeProvisional) { }
    public record GroupMember(String alias, Integer rank, long penalties, long resistancePoints,
                              boolean hasSmokingReport, long missingPastDays, boolean provisional,
                              Double resistancePercentage, List<Day> days) { }
    public record CompletedGap(Instant completedAt, double seconds) { }
    public record Personal(String alias, Double sinceLastSeconds, Double meanCompletedGapSeconds,
                           Double maxCompletedGapSeconds, List<CompletedGap> completedGaps) { }
    public record Insights(LocalDate startDate, LocalDate today, String timeZone, boolean selfReported,
                           List<GroupMember> group, Personal personal) { }

    private final EventStore store;
    private final RageQuitProperties properties;
    private final Clock clock;

    public InsightsService(EventStore store, RageQuitProperties properties, Clock clock) {
        this.store = store;
        this.properties = properties;
        this.clock = clock;
    }

    public Insights insights(String subject) {
        Participants.Participant owner = Participants.requireSubject(subject);
        EventStore.Snapshot snapshot = store.snapshot();
        Instant now = clock.instant();
        LocalDate today = now.atZone(ROME).toLocalDate();
        List<GroupMember> group = new ArrayList<>();
        for (var participant : Participants.ALL) {
            var events = snapshot.events().stream().filter(e -> e.owner().equals(participant.subject()))
                    .map(EventStore.OwnedEvent::event).filter(e -> !e.effectiveAt().isAfter(now)).toList();
            group.add(member(participant.alias(), events,
                    snapshot.zeros().getOrDefault(participant.subject(), Map.of()), today));
        }
        group.sort(Comparator.comparing((GroupMember p) -> !p.hasSmokingReport())
                .thenComparingLong(GroupMember::penalties).thenComparing(GroupMember::alias));
        List<GroupMember> ranked = new ArrayList<>();
        Long previous = null;
        int position = 0;
        int rank = 0;
        for (var member : group) {
            Integer assigned = null;
            if (member.hasSmokingReport()) {
                position++;
                if (previous == null || previous != member.penalties()) rank = position;
                assigned = rank;
                previous = member.penalties();
            }
            ranked.add(new GroupMember(member.alias(), assigned, member.penalties(), member.resistancePoints(),
                    member.hasSmokingReport(), member.missingPastDays(), member.provisional(),
                    member.resistancePercentage(), member.days()));
        }
        var personalEvents = snapshot.events().stream().filter(e -> e.owner().equals(subject))
                .map(EventStore.OwnedEvent::event).filter(e -> e.type() == EventType.SMOKED && !e.effectiveAt().isAfter(now))
                .sorted(Comparator.comparing(Event::effectiveAt).thenComparing(Event::id)).toList();
        return new Insights(properties.startDate(), today, ROME.getId(), true, List.copyOf(ranked),
                intervals(owner.alias(), personalEvents, now));
    }

    private GroupMember member(String alias, List<Event> events, Map<LocalDate, Instant> zeros, LocalDate today) {
        Map<LocalDate, long[]> counts = new HashMap<>();
        for (Event event : events) {
            LocalDate date = event.effectiveAt().atZone(ROME).toLocalDate();
            long[] day = counts.computeIfAbsent(date, ignored -> new long[2]);
            day[event.type() == EventType.SMOKED ? 0 : 1]++;
        }
        List<Day> days = new ArrayList<>();
        long penalties = 0;
        long resistances = 0;
        long missing = 0;
        boolean hasReport = false;
        for (LocalDate date = properties.startDate(); !date.isAfter(today); date = date.plusDays(1)) {
            long[] countsForDay = counts.getOrDefault(date, new long[2]);
            boolean inProgress = date.equals(today);
            SmokingReport report = countsForDay[0] > 0 ? SmokingReport.SMOKED
                    : zeros.containsKey(date) ? SmokingReport.ZERO_CONFIRMED : SmokingReport.MISSING;
            hasReport |= report != SmokingReport.MISSING;
            if (!inProgress && report == SmokingReport.MISSING) missing++;
            penalties += countsForDay[0];
            resistances += countsForDay[1];
            days.add(new Day(date, report == SmokingReport.MISSING ? null : countsForDay[0], countsForDay[1], report,
                    inProgress, penalties, hasReport ? penalties : null, resistances, missing, missing > 0 || inProgress));
        }
        Double percentage = penalties + resistances == 0 ? null : 100.0 * resistances / (penalties + resistances);
        // The displayed period includes today, so even otherwise complete coverage is currently provisional.
        return new GroupMember(alias, null, penalties, resistances, hasReport, missing, true, percentage, List.copyOf(days));
    }

    private static Personal intervals(String alias, List<Event> smoking, Instant now) {
        List<CompletedGap> gaps = new ArrayList<>();
        for (int i = 1; i < smoking.size(); i++) {
            gaps.add(new CompletedGap(smoking.get(i).effectiveAt(), seconds(smoking.get(i - 1).effectiveAt(), smoking.get(i).effectiveAt())));
        }
        Double sinceLast = smoking.isEmpty() ? null : seconds(smoking.getLast().effectiveAt(), now);
        Double mean = gaps.isEmpty() ? null : gaps.stream().mapToDouble(CompletedGap::seconds).average().orElseThrow();
        Double maximum = gaps.isEmpty() ? null : gaps.stream().mapToDouble(CompletedGap::seconds).max().orElseThrow();
        return new Personal(alias, sinceLast, mean, maximum, List.copyOf(gaps));
    }

    private static double seconds(Instant from, Instant to) {
        Duration duration = Duration.between(from, to);
        return duration.getSeconds() + duration.getNano() / 1_000_000_000.0;
    }
}
