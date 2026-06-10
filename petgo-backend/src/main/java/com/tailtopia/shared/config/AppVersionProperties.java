package com.tailtopia.shared.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * App 版本提醒配置（Story 6.5，外部化配置，运营可调，env 注入；<b>不引入新中间件</b>）。
 * 前缀 {@code petgo.app-version}。{@code minSupportedVersion} 用于强制更新判定。
 */
@ConfigurationProperties(prefix = "petgo.app-version")
public class AppVersionProperties {

    private String latest = "1.0.0";
    private String minSupported = "1.0.0";
    private String iosStoreUrl = "";
    private String androidStoreUrl = "";

    public String getLatest() {
        return latest;
    }

    public void setLatest(String latest) {
        this.latest = latest;
    }

    public String getMinSupported() {
        return minSupported;
    }

    public void setMinSupported(String minSupported) {
        this.minSupported = minSupported;
    }

    public String getIosStoreUrl() {
        return iosStoreUrl;
    }

    public void setIosStoreUrl(String iosStoreUrl) {
        this.iosStoreUrl = iosStoreUrl;
    }

    public String getAndroidStoreUrl() {
        return androidStoreUrl;
    }

    public void setAndroidStoreUrl(String androidStoreUrl) {
        this.androidStoreUrl = androidStoreUrl;
    }
}
