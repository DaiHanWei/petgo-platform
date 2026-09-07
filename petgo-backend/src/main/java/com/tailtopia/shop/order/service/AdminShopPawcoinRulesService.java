package com.tailtopia.shop.order.service;

import com.tailtopia.admin.audit.service.AdminAuditService;
import com.tailtopia.admin.audit.service.AuditActions;
import com.tailtopia.config.domain.PawCoinConfig;
import com.tailtopia.config.repository.PawCoinConfigRepository;
import com.tailtopia.shared.error.AppException;
import com.tailtopia.shop.order.domain.ShopPawcoinRules;
import com.tailtopia.shop.order.repository.ShopPawcoinRulesRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * PawCoin 电商消费规则的后台维护（Story 3.5，AB-6D + AB-6A 扩展）。
 *
 * <p>🔴 <b>单笔上限的用途是「故障/欺诈的爆炸半径 + DEP-7 监管姿态」，不是控浮存</b>（L-7 自纠）。
 * 定低反而有害：只会把大额订单挤到纯现金，<b>既不减少浮存，又损失了 Coin 的消耗出口</b>。
 * 浮存超预期时的处置是<b>收紧 AB-6A 溢价比例</b>，不是下调单笔上限。
 *
 * <p>🔴 <b>AB-6C 浮存监控的口径方向</b>（L-7 / L-13）：
 * 电商消费<b>加快存量余额的核销速度 → 预期浮存下降</b>。
 * <b>绝不可写成「为买粮而充值会推高浮存」</b>——方向相反：用户花掉 Coin = 平台交付价值、负债核销。
 */
@Service
public class AdminShopPawcoinRulesService {

    /** 🔴 AB-6C 监控视图的口径说明。写反方向会让运营在浮存告警时做出恰好相反的处置。 */
    public static final String FLOAT_MONITOR_COPY =
            "电商消费会加快存量余额的核销速度 —— 预期浮存下降。"
                    + "浮存超预期时应收紧 AB-6A 溢价比例，而不是下调单笔上限。";

    private final ShopPawcoinRulesRepository rules;
    private final PawCoinConfigRepository pawcoinConfig;
    private final AdminAuditService audit;

    public AdminShopPawcoinRulesService(ShopPawcoinRulesRepository rules,
            PawCoinConfigRepository pawcoinConfig, AdminAuditService audit) {
        this.rules = rules;
        this.pawcoinConfig = pawcoinConfig;
        this.audit = audit;
    }

    @Transactional(readOnly = true)
    public ShopPawcoinRules current() {
        return rules.findById(ShopPawcoinRules.SINGLETON_ID)
                .orElseThrow(() -> AppException.notFound("PawCoin 电商规则未初始化"));
    }

    /**
     * 更新 AB-6D 三项。
     *
     * <p>⚠️ <b>总开关关闭只影响新下单，不影响已付款订单</b>（S-5）——
     * 已付款订单的退款仍按<b>下单时固化的比例</b>执行（那三段金额存在订单上，与本开关无关）。
     * 这样就不构成 FR-100A 规则 5 所说的「对已付款用户违约」，两处定性冲突解除。
     * 🔴 因此本方法<b>不得</b>去改任何既有订单的拆分字段。
     */
    @Transactional
    public ShopPawcoinRules update(boolean enabled, boolean allowShippingDeduction,
            long maxCoinPerOrder, long actorAccountId) {
        if (maxCoinPerOrder < 0) {
            throw AppException.validation("单笔上限不能为负");
        }
        ShopPawcoinRules r = current();
        r.apply(enabled, allowShippingDeduction, maxCoinPerOrder);
        audit.record(actorAccountId, AuditActions.SHOP_PAWCOIN_RULES_UPDATED,
                "SHOP_PAWCOIN_RULES", "1",
                "电商 PawCoin 规则：开关=%s 运费可抵扣=%s 单笔上限=%d"
                        .formatted(enabled, allowShippingDeduction, maxCoinPerOrder));
        return rules.save(r);
    }

    /**
     * 更新<b>平台责任补偿溢价</b>（AB-6A 扩展，C-9 / D-8）。
     *
     * <p>🔴 <b>只动补偿溢价，绝不触碰激励溢价</b>——两者是独立配置项。
     */
    @Transactional
    public PawCoinConfig updateCompensationPremium(int rate, long cap, long actorAccountId) {
        if (rate < 0 || rate > 100) {
            throw AppException.validation("补偿溢价比例应在 0–100 之间");
        }
        if (cap < 0) {
            throw AppException.validation("补偿溢价上限不能为负");
        }
        PawCoinConfig c = pawcoinConfig.findAll().stream().findFirst()
                .orElseThrow(() -> AppException.notFound("PawCoin 配置未初始化"));
        c.applyCompensationPremium(rate, cap);
        audit.record(actorAccountId, AuditActions.SHOP_PAWCOIN_RULES_UPDATED,
                "PAWCOIN_CONFIG", "1",
                "平台责任补偿溢价：比例=%d%% 上限=%d".formatted(rate, cap));
        return pawcoinConfig.save(c);
    }
}
