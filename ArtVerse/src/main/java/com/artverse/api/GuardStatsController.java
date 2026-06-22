package com.artverse.api;

import com.artverse.guard.GuardStatsService;
import com.artverse.guard.GuardEventService;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/internal/guard")
@RequiredArgsConstructor
public class GuardStatsController {

    private final GuardStatsService guardStatsService;
    private final GuardEventService guardEventService;

    @GetMapping("/stats")
    public Map<String, Object> stats() {
        return guardStatsService.stats();
    }

    @GetMapping("/events")
    public Map<String, Object> events(@RequestParam(defaultValue = "100") int limit) {
        List<Map<String, Object>> events = guardEventService.recentEvents(limit);
        return Map.of("events", events);
    }

    @GetMapping("/metrics")
    public Map<String, Object> metrics(@RequestParam(defaultValue = "HOUR") String bucket,
                                       @RequestParam(defaultValue = "24") int range) {
        return guardStatsService.metricBuckets(bucket, range);
    }
}
