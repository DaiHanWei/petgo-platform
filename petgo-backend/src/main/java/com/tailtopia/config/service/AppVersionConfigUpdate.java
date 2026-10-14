package com.tailtopia.config.service;

/** App 版本配置写入命令（V1.3.4 Story 3.1；供 admin 主题保存接口调用，AD-1）。 */
public record AppVersionConfigUpdate(String iosLatestVersion, String androidLatestVersion,
        String minSupportedVersion, String iosStoreUrl, String androidStoreUrl) {
}
