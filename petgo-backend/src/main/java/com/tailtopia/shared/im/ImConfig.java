package com.tailtopia.shared.im;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * IM 基础设施装配（Story 5.5）。绑定 {@link ImProperties}（前缀 {@code petgo.im}）并按 {@code mode} 选实现：
 * <ul>
 *   <li>{@code mode=live} → {@link LiveTencentImClient}（TLSSigAPIv2 签 UserSig + REST 建号/系统消息）。
 *       UserSig 签名是 L0 确定性可验；真实 REST/真机收发属 L2（需 SDKAppID/SecretKey + 数据中心，待本地）。</li>
 *   <li>否则（默认 {@code stub}）→ {@link StubTencentImClient}（免凭证验状态机，L0/L1）。</li>
 * </ul>
 */
@Configuration
@EnableConfigurationProperties(ImProperties.class)
public class ImConfig {

    @Bean
    public TencentImClient tencentImClient(ImProperties props) {
        if ("live".equalsIgnoreCase(props.getMode())) {
            return new LiveTencentImClient(props);
        }
        return new StubTencentImClient(props);
    }
}
