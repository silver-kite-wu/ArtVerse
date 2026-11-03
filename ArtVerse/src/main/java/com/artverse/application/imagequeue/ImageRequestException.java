package com.artverse.application.imagequeue;

import lombok.Getter;
import java.util.*;

@Getter
public class ImageRequestException extends RuntimeException {
    private final int httpStatus;
    private final int numericCode;
    private final Map<String,Object> data;
    public ImageRequestException(String code, String message) { this(code,message,Map.of()); }
    public ImageRequestException(String code, String message, Map<String,Object> details) {
        super(message);
        int number = switch(code) {
            case "INVALID_INPUT" -> 40060; case "IMAGE_VERSION_REQUIRED" -> 40061;
            case "PROVIDER_REQUIRED" -> 40062; case "PROVIDER_CONFIG_INVALID" -> 40063;
            case "IDEMPOTENCY_CONFLICT" -> 40960; case "IMAGE_GENERATION_IN_PROGRESS" -> 40961;
            case "TARGET_CONFLICT" -> 40962; case "INPUT_STALE" -> 40963;
            case "RECOVERY_CONFLICT" -> 40964; case "EXTERNAL_CALL_UNRESOLVED" -> 40965;
            case "CONFIG_CHANGED" -> 40966; case "RESULT_UNCERTAIN" -> 40967;
            case "RATE_LIMITED" -> 42960; case "PROVIDER_AUTH_FAILED" -> 50260;
            case "SUBMISSION_IN_PROGRESS" -> 50360; case "QUEUE_UNAVAILABLE" -> 50361;
            case "PUBLISH_OUTCOME_UNKNOWN" -> 50362; case "UNROUTABLE" -> 50363;
            case "RECOVERY_UNAVAILABLE" -> 50365; default -> 50364;
        };
        numericCode=number; httpStatus=number/100;
        var payload=new LinkedHashMap<String,Object>(details);payload.put("error_code",code);data=Collections.unmodifiableMap(payload);
    }
}
