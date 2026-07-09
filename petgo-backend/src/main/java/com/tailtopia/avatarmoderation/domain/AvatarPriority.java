package com.tailtopia.avatarmoderation.domain;

/**
 * 头像审核人工队列优先级（内容审核 story 5，落库 UPPER_SNAKE）。
 * {@code HIGH} = 图像高置信违规（IMAGE_BLOCKED）或综合风险分 ≥0.8（对应 §5.1 P1）；其余入队为 {@code NORMAL}
 * （含 fail-closed 降级）。
 */
public enum AvatarPriority {
    NORMAL,
    HIGH
}
