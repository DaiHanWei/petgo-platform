package com.tailtopia.passport.event;

import com.tailtopia.passport.domain.PassportSource;

/**
 * 护照首次签发（V1.3.2 Story 1.2 · AC7.2）。只在真正插入新行时发布；埋点在 AFTER_COMMIT 消费。
 *
 * <p>🛡 不带护照号、宠物名（埋点属性红线）。
 */
public record PassportIssuedEvent(long userId, PassportSource source) {
}
