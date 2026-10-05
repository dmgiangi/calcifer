package tech.calcifer.ragequit;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.util.UUID;

import static org.assertj.core.api.Assertions.*;
import static tech.calcifer.ragequit.TestSupport.*;
import static tech.calcifer.ragequit.TrackingDtos.*;
import static tech.calcifer.ragequit.InsightsService.*;

class InsightsTest {
    @TempDir Path directory;
    Fixture fixture;

    @BeforeEach void setup() { fixture = fixture(directory.resolve("insights.sqlite"), new MutableClock(NOW), START); }

    @Test void emptyAndResistanceOnlyHistoriesAreUnrankedWithUnavailableSmokingData() {
        var empty = fixture.insights().insights(DEM);
        assertThat(empty.group()).allSatisfy(member -> {
            assertThat(member.rank()).isNull();
            assertThat(member.hasSmokingReport()).isFalse();
            assertThat(member.resistancePercentage()).isNull();
            assertThat(member.missingPastDays()).isEqualTo(2);
            assertThat(member.days()).allSatisfy(day -> {
                assertThat(day.penalties()).isNull();
                assertThat(day.cumulativePenalties()).isNull();
                assertThat(day.smokingReport()).isEqualTo(SmokingReport.MISSING);
            });
        });
        assertThat(empty.personal().sinceLastSeconds()).isNull();
        assertThat(empty.personal().meanCompletedGapSeconds()).isNull();
        assertThat(empty.personal().maxCompletedGapSeconds()).isNull();
        add(FREVA, EventType.RESISTED, NOW);
        var resistanceOnly = member(fixture.insights().insights(FREVA), "Frevadiscor");
        assertThat(resistanceOnly.rank()).isNull();
        assertThat(resistanceOnly.resistancePoints()).isEqualTo(1);
        assertThat(resistanceOnly.resistancePercentage()).isEqualTo(100.0);
        assertThat(resistanceOnly.days().getLast().penalties()).isNull();
        assertThat(resistanceOnly.days().getLast().resistancePoints()).isEqualTo(1);
    }

    @Test void twoCigarettesAndThreeResistancesNeverBecomeANetScore() {
        add(DEM, EventType.SMOKED, NOW);
        add(DEM, EventType.SMOKED, NOW.minusSeconds(1));
        for (int i = 0; i < 3; i++) add(DEM, EventType.RESISTED, NOW.minusSeconds(10 + i));
        GroupMember dem = member(fixture.insights().insights(DEM), "Dem");
        Day today = dem.days().getLast();
        assertThat(today.penalties()).isEqualTo(2);
        assertThat(today.resistancePoints()).isEqualTo(3);
        assertThat(today.inProgress()).isTrue();
        assertThat(today.cumulativeProvisional()).isTrue();
        assertThat(dem.penalties()).isEqualTo(2);
        assertThat(dem.resistancePoints()).isEqualTo(3);
    }

    @Test void tiesShareCompetitionRanksAndResistanceNeverBreaksThem() {
        add(DEM, EventType.SMOKED, NOW);
        add(PUGLIENS, EventType.SMOKED, NOW);
        for (int i = 0; i < 3; i++) add(PUGLIENS, EventType.RESISTED, NOW);
        add(FREVA, EventType.SMOKED, NOW);
        add(FREVA, EventType.SMOKED, NOW);
        var insights = fixture.insights().insights(DEM);
        assertThat(member(insights, "Dem").rank()).isEqualTo(1);
        assertThat(member(insights, "Pugliens").rank()).isEqualTo(1);
        assertThat(member(insights, "Frevadiscor").rank()).isEqualTo(3);
        assertThat(insights.group()).extracting(GroupMember::alias).containsExactly("Dem", "Pugliens", "Frevadiscor");
        assertThat(insights.group()).allSatisfy(member -> assertThat(member.days()).extracting(Day::date)
                .containsExactly(START, START.plusDays(1), START.plusDays(2)));
    }

    @Test void confirmedZeroIsRankedButMissingAndCurrentDayRemainVisible() {
        fixture.store().confirmZero(DEM, START);
        fixture.store().confirmZero(DEM, fixture.store().today());
        GroupMember dem = member(fixture.insights().insights(DEM), "Dem");
        assertThat(dem.rank()).isEqualTo(1);
        assertThat(dem.penalties()).isZero();
        assertThat(dem.missingPastDays()).isEqualTo(1);
        assertThat(dem.provisional()).isTrue();
        assertThat(dem.days().getFirst().penalties()).isZero();
        assertThat(dem.days().getFirst().cumulativeProvisional()).isFalse();
        assertThat(dem.days().get(1).penalties()).isNull();
        assertThat(dem.days().get(1).cumulativePenalties()).isZero();
        assertThat(dem.days().get(1).cumulativeMissingPastDays()).isEqualTo(1);
        assertThat(dem.days().get(1).cumulativeProvisional()).isTrue();
        assertThat(dem.days().getLast().inProgress()).isTrue();
        assertThat(dem.days().getLast().smokingReport()).isEqualTo(SmokingReport.ZERO_CONFIRMED);
        fixture.store().withdrawZero(DEM, START);
        fixture.store().withdrawZero(DEM, fixture.store().today());
        assertThat(member(fixture.insights().insights(DEM), "Dem").rank()).isNull();
    }

    @ParameterizedTest @ValueSource(strings = {"2026-03-29", "2026-10-25"})
    void dailyBoundariesRespectBothDstTransitions(String value) {
        LocalDate date = LocalDate.parse(value);
        Instant start = date.atStartOfDay(ROME).toInstant();
        Instant next = date.plusDays(1).atStartOfDay(ROME).toInstant();
        long hours = Duration.between(start, next).toHours();
        assertThat(hours).isEqualTo(date.getMonthValue() == 3 ? 23 : 25);
        Fixture calendar = fixture(directory.resolve("dst-" + value + ".sqlite"), new MutableClock(next.plusSeconds(3600)), date.minusDays(1));
        calendar.store().create(DEM, create(EventType.SMOKED, "before", start.minusNanos(1)));
        calendar.store().create(DEM, create(EventType.SMOKED, "start", start));
        calendar.store().create(DEM, create(EventType.SMOKED, "end", next.minusNanos(1)));
        calendar.store().create(DEM, create(EventType.RESISTED, "next", next));
        var days = member(calendar.insights().insights(DEM), "Dem").days();
        assertThat(days.getFirst().penalties()).isEqualTo(1);
        assertThat(days.get(1).penalties()).isEqualTo(2);
        assertThat(days.getLast().penalties()).isNull();
        assertThat(days.getLast().resistancePoints()).isEqualTo(1);
        assertThat(days.getLast().recordedCumulativePenalties()).isEqualTo(3);
    }

    @Test void oneCigaretteHasOnlyAnOpenIntervalAndResistanceDoesNotResetIt() {
        add(DEM, EventType.SMOKED, NOW.minusSeconds(3600));
        add(DEM, EventType.RESISTED, NOW.minusSeconds(600));
        Personal personal = fixture.insights().insights(DEM).personal();
        assertThat(personal.sinceLastSeconds()).isEqualTo(3600.0);
        assertThat(personal.meanCompletedGapSeconds()).isNull();
        assertThat(personal.maxCompletedGapSeconds()).isNull();
        assertThat(personal.completedGaps()).isEmpty();
    }

    @ParameterizedTest @ValueSource(strings = {"2026-03-28", "2026-10-24", "2026-03-30"})
    void overnightCompletedUtcGapsAccountForDstAndExcludeOpenInterval(String value) {
        LocalDate date = LocalDate.parse(value);
        Instant first = date.atTime(LocalTime.of(23, 0)).atZone(ROME).toInstant();
        Instant second = date.plusDays(1).atTime(LocalTime.of(9, 0)).atZone(ROME).toInstant();
        Fixture gaps = fixture(directory.resolve("gaps-" + value + ".sqlite"), new MutableClock(second.plusSeconds(100_000)), date);
        gaps.store().create(DEM, create(EventType.SMOKED, "first", first));
        gaps.store().create(DEM, create(EventType.RESISTED, "middle", first.plusSeconds(3600)));
        gaps.store().create(DEM, create(EventType.SMOKED, "second", second));
        double expected = (date.getMonthValue() == 10 ? 11 : date.getDayOfMonth() == 28 ? 9 : 10) * 3600.0;
        Personal personal = gaps.insights().insights(DEM).personal();
        assertThat(personal.meanCompletedGapSeconds()).isEqualTo(expected);
        assertThat(personal.maxCompletedGapSeconds()).isEqualTo(expected);
        assertThat(personal.sinceLastSeconds()).isEqualTo(100_000.0);
        assertThat(personal.completedGaps()).containsExactly(new CompletedGap(second, expected));
    }

    @Test void equalTimestampsAreZeroGapsAndCorrectionsRecomputeEverything() {
        Event first = add(DEM, EventType.SMOKED, NOW.minusSeconds(7200));
        Event second = add(DEM, EventType.SMOKED, NOW.minusSeconds(3600));
        add(DEM, EventType.SMOKED, NOW.minusSeconds(3600));
        Personal initial = fixture.insights().insights(DEM).personal();
        assertThat(initial.meanCompletedGapSeconds()).isEqualTo(1800.0);
        assertThat(initial.maxCompletedGapSeconds()).isEqualTo(3600.0);
        assertThat(initial.completedGaps()).extracting(CompletedGap::seconds).containsExactly(3600.0, 0.0);
        fixture.store().update(DEM, first.id(), new UpdateEvent(1L, EventType.RESISTED, at("2026-03-28T12:00:00Z")));
        var corrected = fixture.insights().insights(DEM);
        assertThat(corrected.personal().meanCompletedGapSeconds()).isZero();
        GroupMember dem = member(corrected, "Dem");
        assertThat(dem.penalties()).isEqualTo(2);
        assertThat(dem.resistancePoints()).isEqualTo(1);
        assertThat(dem.days().getFirst().penalties()).isNull();
        assertThat(dem.days().getFirst().resistancePoints()).isEqualTo(1);
        assertThat(dem.days().getLast().recordedCumulativePenalties()).isEqualTo(dem.penalties());
        assertThat(dem.days().getLast().cumulativeResistancePoints()).isEqualTo(dem.resistancePoints());
        fixture.store().delete(DEM, second.id(), 1);
        assertThat(fixture.insights().insights(DEM).personal().meanCompletedGapSeconds()).isNull();
    }

    @Test void backdatedInsertionAndMoveReorderCompletedGaps() {
        add(DEM, EventType.SMOKED, NOW.minusSeconds(3600));
        Event earlier = add(DEM, EventType.SMOKED, NOW.minusSeconds(10800));
        add(DEM, EventType.SMOKED, NOW.minusSeconds(7200));
        assertThat(fixture.insights().insights(DEM).personal().completedGaps()).extracting(CompletedGap::seconds)
                .containsExactly(3600.0, 3600.0);
        fixture.store().update(DEM, earlier.id(), new UpdateEvent(1L, EventType.SMOKED, new TimeInput(null, NOW, null, null)));
        assertThat(fixture.insights().insights(DEM).personal().completedGaps()).extracting(CompletedGap::seconds)
                .containsExactly(3600.0, 3600.0);
        assertThat(fixture.insights().insights(DEM).personal().sinceLastSeconds()).isZero();
    }

    @Test void percentageUsesOnlyRegisteredEpisodesAndGroupHasNoOtherUsersRawIntervals() {
        add(DEM, EventType.SMOKED, NOW.minusSeconds(3600));
        for (int i = 0; i < 3; i++) add(DEM, EventType.RESISTED, NOW);
        assertThat(member(fixture.insights().insights(DEM), "Dem").resistancePercentage()).isEqualTo(75.0);
        assertThat(fixture.insights().insights(PUGLIENS).personal().sinceLastSeconds()).isNull();
        assertThat(fixture.insights().insights(PUGLIENS).selfReported()).isTrue();
        assertThat(fixture.insights().insights(PUGLIENS).timeZone()).isEqualTo("Europe/Rome");
    }

    private Event add(String owner, EventType type, Instant time) {
        return fixture.store().create(owner, create(type, UUID.randomUUID().toString(), time));
    }

    private static GroupMember member(Insights insights, String alias) {
        return insights.group().stream().filter(p -> p.alias().equals(alias)).findFirst().orElseThrow();
    }
}
