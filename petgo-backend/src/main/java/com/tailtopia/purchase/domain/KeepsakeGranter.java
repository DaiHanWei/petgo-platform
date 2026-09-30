package com.tailtopia.purchase.domain;

/**
 * 发放口（V1.3.2 Story 3.1 · AD-1）：<b>定义在 purchase 包，由 SKU 模块实现</b>（Story 3.2 / 3.4 / 3.5 交付）。
 *
 * <p>Spring 按 {@link #sku()} 收集；启动时校验三个 SKU 各恰好一个实现（缺失 / 重复 → 启动失败）。
 *
 * <p>🔴 契约：{@link #grant} <b>不得抛异常</b>，在调用方的事务里同步执行（到账时即 {@code applyCallback} 的事务）。
 * 实现<b>不要</b>用会在抛出时把外层事务标成 rollback-only 的写法 —— 那会连带回滚 {@code markPaid}。
 */
public interface KeepsakeGranter {

    KeepsakeSku sku();

    /** 把业务行 {@code refId} 置为已解锁。幂等：已解锁返回 {@link GrantOutcome#ALREADY_UNLOCKED}。 */
    GrantOutcome grant(long refId, long purchaseId);
}
