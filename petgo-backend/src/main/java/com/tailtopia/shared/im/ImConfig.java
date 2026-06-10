package com.tailtopia.shared.im;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * IM 基础设施装配（Story 5.5）。绑定 {@link ImProperties}（前缀 {@code petgo.im}）并按 {@code mode} 选实现：
 * <ul>
 *   <li>{@code mode=live} → 真实腾讯 IM（L2，需 SDKAppID/SecretKey + REST/SDK 接入）——<b>本批次未实现</b>，
 *       启动即抛错提示接入（避免静默用桩冒充真实 IM）。</li>
 *   <li>否则（默认 {@code stub}）→ {@link StubTencentImClient}（免凭证验状态机，L0/L1）。</li>
 * </ul>
 */
@Configuration
@EnableConfigurationProperties(ImProperties.class)
public class ImConfig {

    @Bean
    public TencentImClient tencentImClient(ImProperties props) {
        if ("live".equalsIgnoreCase(props.getMode())) {
            // 真实腾讯 IM 接入是 L2（需真机 + 真实 SDKAppID/SecretKey + TLSSigAPIv2/REST）。
            // 本批次（云端 headless）不实现 live，以免伪造凭证；本地接入时在此装配 LiveTencentImClient。
            throw new IllegalStateException(
                    "IM live 模式需接入腾讯 IM REST/SDK（L2 待本地实现）；云端请用 petgo.im.mode=stub");
        }
        return new StubTencentImClient(props);
    }
}
