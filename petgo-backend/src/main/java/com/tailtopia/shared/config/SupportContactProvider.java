package com.tailtopia.shared.config;

import org.springframework.stereotype.Component;

/**
 * 客服联系方式来源（Story 3-1 / AD-S8）。范式照 {@code pay/service/TopupTierProvider}：
 * 接口 + 内置 {@link Default} 兜底，DB 实现挂 {@code @Primary} 覆盖它。
 *
 * <p><b>为什么放 {@code shared/config}</b>：{@code auth/}（登录被拒的文案）与
 * {@code support/}（免鉴权下发端点）都要用它，谁也不该依赖谁。{@code @Primary} 的 DB 实现
 * 放在 {@code config/service}（表归它管），这样 {@code shared} 不反向依赖 {@code config}。
 *
 * <p>🛡 <b>不加任何缓存层</b>（护栏：禁通用缓存）。单行直读，与
 * {@code PlatformConfigService} 同一姿态。
 */
public interface SupportContactProvider {

    /** 当前客服联系方式。<b>实现必须保证永不抛异常</b>——见 {@link Default} 的说明。 */
    SupportContact contact();

    /**
     * 内置默认实现：硬编码当前号码，DB 读不到时回退。
     *
     * <p>🔴 <b>这份兜底不是冗余，是防「一起挂」</b>：客服号的消费方里有**登录被拒的提示文案**
     * 与**兽医登录页的客服弹窗**。配置表出问题时若抛异常，用户会连「找谁申诉」都看不到 ——
     * 而那正是他最需要看到客服号的时刻。
     */
    @Component
    class Default implements SupportContactProvider {

        /** SD-10 已定用此号（备用号 081399779133 全仓零命中，不要顺手加回来）。 */
        static final String FALLBACK_WHATSAPP = "081290906953";
        static final String FALLBACK_EMAIL = "cs@tailtopia.id";

        private static final SupportContact FALLBACK =
                SupportContact.of(FALLBACK_WHATSAPP, FALLBACK_EMAIL);

        @Override
        public SupportContact contact() {
            return FALLBACK;
        }
    }
}
