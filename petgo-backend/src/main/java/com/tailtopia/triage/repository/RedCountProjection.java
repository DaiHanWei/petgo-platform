package com.tailtopia.triage.repository;

/** 按用户聚合 RED 分诊计数投影（Story 9.6 红色超额监控）。 */
public interface RedCountProjection {
    long getUserId();

    long getRedCount();
}
