package com.petgo.consult.domain;

/**
 * 咨询发起来源（Story 5.3）。{@link #DIRECT} 用户直接发起；{@link #AI_UPGRADE} 从 AI 分诊升级（Story 5.4）。
 */
public enum ConsultSource {
    DIRECT,
    AI_UPGRADE
}
