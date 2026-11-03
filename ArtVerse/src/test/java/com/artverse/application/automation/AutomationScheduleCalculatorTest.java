package com.artverse.application.automation;

import com.artverse.common.BusinessException;
import org.junit.jupiter.api.Test;
import java.time.Instant;
import java.util.List;
import static org.assertj.core.api.Assertions.*;

class AutomationScheduleCalculatorTest {
    private final AutomationScheduleCalculator calculator=new AutomationScheduleCalculator();
    private AutomationDefinition.Schedule daily(String time,String zone) { return calculator.normalize(new AutomationDefinition.Schedule("DAILY",null,time,zone,null)); }
    @Test void dailyUsesUsersLocalCalendarAndDoesNotRepeatSameInstant() {
        var schedule=daily("09:00","Asia/Shanghai");
        assertThat(calculator.nextAfter(schedule,Instant.parse("2026-10-25T00:00:00Z"))).isEqualTo(Instant.parse("2026-10-25T01:00:00Z"));
        assertThat(calculator.nextAfter(schedule,Instant.parse("2026-10-25T01:00:00Z"))).isEqualTo(Instant.parse("2026-10-26T01:00:00Z"));
    }
    @Test void weeklySkipsUnselectedDaysAndNormalizesDuplicates() {
        var schedule=calculator.normalize(new AutomationDefinition.Schedule("WEEKLY",null,"09:00","Asia/Shanghai",List.of(5,1,1)));
        assertThat(schedule.weekdays()).containsExactly(1,5);
        assertThat(calculator.nextAfter(schedule,Instant.parse("2026-10-25T01:00:00Z"))).isEqualTo(Instant.parse("2026-10-26T01:00:00Z"));
    }
    @Test void springGapIsSkippedAndDailyTimeIsNotFixed24Hours() {
        var schedule=daily("02:30","America/New_York");
        assertThat(calculator.nextAfter(schedule,Instant.parse("2026-03-07T08:00:00Z"))).isEqualTo(Instant.parse("2026-03-09T06:30:00Z"));
        var morning=daily("09:00","America/New_York");
        assertThat(calculator.nextAfter(morning,Instant.parse("2026-03-07T14:00:00Z"))).isEqualTo(Instant.parse("2026-03-08T13:00:00Z"));
    }
    @Test void fallOverlapUsesEarlierOffsetOnlyOnce() {
        var schedule=daily("01:30","America/New_York");
        assertThat(calculator.nextAfter(schedule,Instant.parse("2026-11-01T04:00:00Z"))).isEqualTo(Instant.parse("2026-11-01T05:30:00Z"));
        assertThat(calculator.nextAfter(schedule,Instant.parse("2026-11-01T05:30:00Z"))).isEqualTo(Instant.parse("2026-11-02T06:30:00Z"));
    }
    @Test void oneShotRejectsGapAndOverlapInsteadOfGuessing() {
        for(String local:List.of("2026-03-08T02:30","2026-11-01T01:30"))
            assertThatThrownBy(()->calculator.normalize(new AutomationDefinition.Schedule("ONCE",local,null,"America/New_York",null))).isInstanceOf(BusinessException.class);
    }
    @Test void oneShotStopsAfterItFires() {
        var schedule=calculator.normalize(new AutomationDefinition.Schedule("ONCE","2026-10-26T09:00",null,"Asia/Shanghai",null));
        assertThat(calculator.nextAfter(schedule,Instant.parse("2026-10-26T01:00:00Z"))).isNull();
    }
    @Test void misfireFindsLatestCalendarOccurrenceWithoutEnumeratingYears() {
        assertThat(calculator.latestAtOrBefore(daily("09:00","Asia/Shanghai"),Instant.parse("2036-10-25T00:00:00Z")))
                .isEqualTo(Instant.parse("2036-10-24T01:00:00Z"));
    }
    @Test void previewAndExecutionUseSameDstRules() {
        var schedule=daily("01:30","America/New_York");
        var preview=calculator.preview(schedule,Instant.parse("2026-11-01T04:00:00Z"),2);
        assertThat(preview).extracting(p->p.get("instant")).containsExactly("2026-11-01T05:30:00Z","2026-11-02T06:30:00Z");
    }
}
