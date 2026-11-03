package com.artverse.application.imagequeue;

import org.springframework.stereotype.Component;
import java.util.UUID;

/** No-op in production; tests inject precise failures without real paid provider requests. */
@Component
public class ImageExecutionFaults {
    public void hit(String boundary,UUID operationId) {}
}
