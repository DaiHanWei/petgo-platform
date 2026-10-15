package com.tailtopia.shared.config;

import com.tailtopia.config.domain.AppVersionConfig;
import com.tailtopia.config.service.AppVersionConfigService;
import com.tailtopia.shared.error.AppException;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * App 版本信息端点（Story 6.5，公开/游客可读，无需 JWT）。
 *
 * <p>{@code GET /api/v1/app-version} → {@code {iosLatestVersion, androidLatestVersion, minSupportedVersion,
 * iosStoreUrl, androidStoreUrl}}。V1.3.4 Story 3.1 起每次读 {@code app_version_config} 表（不缓存），
 * 改值走后台 AB-22，无需重启。配置行缺失 → 503（App 侧失败放行），不返回硬编码兜底值。
 * App 内提示用，<b>不走系统推送、不需推送权限</b>。
 */
@RestController
public class AppVersionController {

    private final AppVersionConfigService service;

    public AppVersionController(AppVersionConfigService service) {
        this.service = service;
    }

    public record AppVersionResponse(String iosLatestVersion, String androidLatestVersion,
            String minSupportedVersion, String iosStoreUrl, String androidStoreUrl) {
    }

    @GetMapping("/api/v1/app-version")
    public AppVersionResponse appVersion() {
        AppVersionConfig c = service.current()
                .orElseThrow(() -> AppException.serviceUnavailable("App 版本配置缺失"));
        return new AppVersionResponse(c.getIosLatestVersion(), c.getAndroidLatestVersion(),
                c.getMinSupportedVersion(), c.getIosStoreUrl(), c.getAndroidStoreUrl());
    }
}
