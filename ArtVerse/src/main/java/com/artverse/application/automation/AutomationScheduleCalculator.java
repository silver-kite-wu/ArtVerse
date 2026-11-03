package com.artverse.application.automation;

import com.artverse.common.BusinessException;
import org.springframework.stereotype.Component;
import java.time.*;
import java.util.*;

/** A local-calendar schedule: gaps are skipped, overlaps use the earlier offset exactly once. */
@Component
public class AutomationScheduleCalculator {
    public AutomationDefinition.Schedule normalize(AutomationDefinition.Schedule input) {
        if (input == null) throw invalid("请选择执行规则");
        try {
            String kind = Objects.toString(input.kind(), "ONCE").toUpperCase(Locale.ROOT);
            String zone = ZoneId.of(input.timezone() == null || input.timezone().isBlank()
                    ? "Asia/Shanghai" : input.timezone()).getId();
            if ("ONCE".equals(kind)) {
                LocalDateTime dateTime = LocalDateTime.parse(input.localDateTime());
                if (ZoneId.of(zone).getRules().getValidOffsets(dateTime).size() != 1)
                    throw invalid("该本地时间不存在或存在夏令时歧义，请选择明确的时间");
                return new AutomationDefinition.Schedule(kind, dateTime.toString(), null, zone, List.of());
            }
            if (!Set.of("DAILY", "WEEKLY").contains(kind)) throw invalid("仅支持单次、每天或每周");
            String time = LocalTime.parse(input.localTime()).withSecond(0).withNano(0).toString();
            List<Integer> days = "DAILY".equals(kind) ? List.of() : input.weekdays();
            if (days == null || ("WEEKLY".equals(kind) && days.isEmpty())
                    || days.stream().anyMatch(d -> d == null || d < 1 || d > 7))
                throw invalid("请选择有效的星期（1 至 7）");
            return new AutomationDefinition.Schedule(kind, null, time, zone, days.stream().distinct().sorted().toList());
        } catch (BusinessException e) { throw e; }
        catch (RuntimeException e) { throw invalid("执行日期、时间或时区无效"); }
    }

    public Instant nextAfter(AutomationDefinition.Schedule schedule, Instant after) {
        ZoneId zone = ZoneId.of(schedule.timezone());
        if ("ONCE".equals(schedule.kind())) {
            Instant instant = LocalDateTime.parse(schedule.localDateTime()).atZone(zone).toInstant();
            return instant.isAfter(after) ? instant : null;
        }
        LocalDate start = after.atZone(zone).toLocalDate();
        for (int i = 0; i < 370; i++) {
            Instant candidate = candidate(schedule, start.plusDays(i), zone);
            if (candidate != null && candidate.isAfter(after)) return candidate;
        }
        throw invalid("无法计算下一次执行时间");
    }

    public Instant latestAtOrBefore(AutomationDefinition.Schedule schedule, Instant now) {
        if ("ONCE".equals(schedule.kind())) {
            Instant instant = LocalDateTime.parse(schedule.localDateTime()).atZone(ZoneId.of(schedule.timezone())).toInstant();
            return !instant.isAfter(now) ? instant : null;
        }
        ZoneId zone = ZoneId.of(schedule.timezone());
        LocalDate start = now.atZone(zone).toLocalDate();
        for (int i = 0; i < 15; i++) {
            Instant candidate = candidate(schedule, start.minusDays(i), zone);
            if (candidate != null && !candidate.isAfter(now)) return candidate;
        }
        throw invalid("无法计算最近执行时间");
    }

    public List<Map<String, String>> preview(AutomationDefinition.Schedule input, Instant now, int count) {
        var schedule = normalize(input);
        var results = new ArrayList<Map<String, String>>();
        Instant cursor = now;
        for (int i = 0; i < Math.min(10, Math.max(1, count)); i++) {
            Instant next = nextAfter(schedule, cursor);
            if (next == null) break;
            results.add(Map.of("instant", next.toString(), "localTime", next.atZone(ZoneId.of(schedule.timezone())).toString()));
            cursor = next;
        }
        return results;
    }

    private Instant candidate(AutomationDefinition.Schedule schedule, LocalDate date, ZoneId zone) {
        if ("WEEKLY".equals(schedule.kind()) && !schedule.weekdays().contains(date.getDayOfWeek().getValue())) return null;
        LocalDateTime local = date.atTime(LocalTime.parse(schedule.localTime()));
        List<ZoneOffset> offsets = zone.getRules().getValidOffsets(local);
        return offsets.isEmpty() ? null : local.toInstant(offsets.getFirst());
    }

    private BusinessException invalid(String message) { return new BusinessException(400, message); }
}
