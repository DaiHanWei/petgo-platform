package com.tailtopia.config.service;

import com.tailtopia.shared.config.SupportContact;
import com.tailtopia.shared.config.SupportContactProvider;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Primary;
import org.springframework.stereotype.Component;

/**
 * DB 后台可配的客服联系方式（Story 3-1 / AD-S8）。{@code @Primary} 覆盖内置
 * {@link SupportContactProvider.Default}。
 *
 * <p>🔴 <b>任何异常都回退兜底，绝不上抛</b>。两种失败都接住：
 * <ul>
 *   <li><b>单行缺失</b>（种子没跑 / 被误删）—— 照 {@code DbTopupTierProvider} 的空集合回退；</li>
 *   <li><b>号码归一化失败</b>（运营存进了一个后来才发现不合法的值）——
 *       {@code IndonesiaPhone.normalize} 会抛，这里接住。</li>
 * </ul>
 * 理由同 {@link SupportContactProvider.Default} 的注释：本值的消费方包括
 * **登录被拒的提示文案**，在这里抛异常等于让用户连「找谁申诉」都看不到。
 *
 * <p>🛡 日志只记失败原因的类名，<b>不记号码本身</b>（{@code IndonesiaPhone} 的 NFR-5 纪律：
 * 号码错误不回显输入）。
 */
@Primary
@Component
public class DbSupportContactProvider implements SupportContactProvider {

    private static final Logger log = LoggerFactory.getLogger(DbSupportContactProvider.class);

    private final PlatformConfigService config;
    private final SupportContactProvider.Default fallback;

    public DbSupportContactProvider(PlatformConfigService config,
            SupportContactProvider.Default fallback) {
        this.config = config;
        this.fallback = fallback;
    }

    @Override
    public SupportContact contact() {
        try {
            return config.supportContact()
                    .map(c -> SupportContact.of(c.getWhatsappNumber(), c.getEmail()))
                    .orElseGet(() -> {
                        log.warn("support_contact_config 单行缺失，回退内置客服联系方式");
                        return fallback.contact();
                    });
        } catch (RuntimeException e) {
            // 归一化失败或读库失败都走这里。埋点式地记一行就够 —— 不记号码。
            log.warn("客服联系方式读取失败，回退内置值：reason={}", e.getClass().getSimpleName());
            return fallback.contact();
        }
    }
}
