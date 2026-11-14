package com.tailtopia.healthreminder.service;

import java.time.Instant;
import org.springframework.stereotype.Component;

/** Story 2.1 空实现；Story 2.3 替换。 */
@Component
public class NoopPregnancyResumeHandler implements PregnancyResumeHandler {

    @Override
    public void onPregnancyCleared(long petId, Instant clearedAt) {
        // Story 2.3 接入
    }
}
