package com.petgo.shared.config;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * App 版本信息端点（Story 6.5，公开/游客可读，无需 JWT）。
 *
 * <p>{@code GET /api/v1/app-version} → {@code {latestVersion, minSupportedVersion, iosStoreUrl, androidStoreUrl}}。
 * App 内提示用，<b>不走系统推送、不需推送权限</b>。前端拿不到时默认放行（不阻断启动）。
 */
@RestController
@EnableConfigurationProperties(AppVersionProperties.class)
public class AppVersionController {

    private final AppVersionProperties props;

    public AppVersionController(AppVersionProperties props) {
        this.props = props;
    }

    public record AppVersionResponse(String latestVersion, String minSupportedVersion,
            String iosStoreUrl, String androidStoreUrl) {
    }

    @GetMapping("/api/v1/app-version")
    public AppVersionResponse appVersion() {
        return new AppVersionResponse(props.getLatest(), props.getMinSupported(),
                props.getIosStoreUrl(), props.getAndroidStoreUrl());
    }
}
