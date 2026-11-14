package com.tailtopia.healthreminder.schedule;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * Story 2.3 的占位投递：只记日志、不发（行保持 PENDING，次日按 missed_run 作废）。Story 2.4 以真实的
 * {@code HealthReminderDispatcher} 替换。上线开关默认关，占位期间日跑不会走到这里。
 */
@Component
public class UndeliveredHealthDispatchPort implements HealthDispatchPort {

    private static final Logger log = LoggerFactory.getLogger(UndeliveredHealthDispatchPort.class);

    @Override
    public DispatchOutcome dispatch(SendGroup group) {
        log.warn("health dispatch not wired petId={} rows={}", group.petId(), group.rows().size());
        return DispatchOutcome.NOT_SENT;
    }
}
