package com.artverse.application.automation;

import java.util.Set;

public interface AutomationExecutor {
    Set<String> keys();
    Observation execute(AutomationStore.Run run);
    Observation cancel(AutomationStore.Run run);

    record Observation(String state,String code,String summary,String waitingReason,int retrySeconds) {
        public static Observation running(String summary) { return new Observation("RUNNING",null,summary,null,5); }
        public static Observation done(String summary) { return new Observation("SUCCEEDED",null,summary,null,0); }
        public static Observation waiting(String reason,String summary) { return new Observation("WAITING_USER",reason,summary,reason,0); }
        public static Observation failed(String code,String summary) { return new Observation("FAILED",code,summary,null,0); }
    }
}
