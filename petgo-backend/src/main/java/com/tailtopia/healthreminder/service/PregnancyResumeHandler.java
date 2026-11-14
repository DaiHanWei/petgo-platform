package com.tailtopia.healthreminder.service;

import java.time.Instant;

/**
 * 怀孕 / 哺乳清除后的恢复处理（V1.5.0 Story 2.1 留调用点，Story 2.3 实现架构增量 §4.6「清除」：疫苗补发表）。
 * 清除不走普通重建。
 */
public interface PregnancyResumeHandler {

    void onPregnancyCleared(long petId, Instant clearedAt);
}
