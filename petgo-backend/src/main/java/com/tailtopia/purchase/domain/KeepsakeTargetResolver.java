package com.tailtopia.purchase.domain;

import java.util.Optional;

/**
 * 一次性解锁购买的「查看」目标解析（V1.3.2 Story 3.6 · AC3.6）：<b>定义在 purchase 包，由 SKU 模块实现</b>
 * （tailsonality / passport 包各一个），订单中心经它只读取 token，<b>不直接查</b> SKU 表。
 */
public interface KeepsakeTargetResolver {

    KeepsakeSku sku();

    /** 目标类型（App 按它选跳转页）。 */
    String targetKind();

    /** @param refId {@code keepsake_purchases.ref_id}；业务行不存在 / 不可看 → empty */
    Optional<String> targetToken(long refId);
}
