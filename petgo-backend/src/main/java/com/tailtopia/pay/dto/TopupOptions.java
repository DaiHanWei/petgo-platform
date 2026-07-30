package com.tailtopia.pay.dto;

import java.util.List;

/**
 * 充值选项（Story 1.5）。前端充值档位页渲染用：可选档位 + 是否暂停。
 *
 * @param tiers  可选充值档位
 * @param paused 充值是否暂停（浮存门槛，运营/工程手动 env 触发；true 时前端渲染暂停态，不渲染档位）
 */
public record TopupOptions(List<TopupTierDto> tiers, boolean paused) {
}
