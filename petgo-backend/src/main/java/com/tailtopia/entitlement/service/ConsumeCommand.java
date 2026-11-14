package com.tailtopia.entitlement.service;

import com.tailtopia.entitlement.domain.CreditType;
import com.tailtopia.entitlement.domain.CreditUseKind;

/**
 * 扣次数命令（Story 4-1 AC2.4 / D-25）。
 *
 * @param type     KTP 传具体款（不得传 ANY，服务内部自动回落到 ANY）；其他传对应类型
 * @param quantity KTP / 性格测试恒为 1；送礼 = 礼物数量
 * @param targetId id_cards.id / tailsonality_results.id / gift_orders.id（幂等键 {@code {kind}:{targetId}}）
 * @param petName  「用在 {宠物}」快照（截到 20 字符，可空）
 */
public record ConsumeCommand(long userId, CreditUseKind kind, CreditType type, int quantity, long targetId,
        String petName) { }
