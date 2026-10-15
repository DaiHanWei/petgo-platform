package com.tailtopia.config.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;

/**
 * App 版本配置（V1.3.4 Story 3.1 / AD-10）。固定单行 {@code id=1}（DB CHECK 保证）。
 *
 * <p>iOS / Android 各一个最新版本号（两端上架不同步）；最低支持版本双端共用。
 * {@code GET /api/v1/app-version} 每次读本表，改值即生效、无需重启。
 */
@Entity
@Table(name = "app_version_config")
public class AppVersionConfig {

    public static final long SINGLETON_ID = 1L;

    @Id
    private Long id;

    @Column(name = "ios_latest_version", nullable = false, length = 20)
    private String iosLatestVersion;

    @Column(name = "android_latest_version", nullable = false, length = 20)
    private String androidLatestVersion;

    @Column(name = "min_supported_version", nullable = false, length = 20)
    private String minSupportedVersion;

    @Column(name = "ios_store_url", nullable = false, length = 512)
    private String iosStoreUrl;

    @Column(name = "android_store_url", nullable = false, length = 512)
    private String androidStoreUrl;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    /** admin_accounts.id（不建外键）；迁移种子为 NULL。 */
    @Column(name = "updated_by")
    private Long updatedBy;

    protected AppVersionConfig() {
    }

    public Long getId() {
        return id;
    }

    public String getIosLatestVersion() {
        return iosLatestVersion;
    }

    public String getAndroidLatestVersion() {
        return androidLatestVersion;
    }

    public String getMinSupportedVersion() {
        return minSupportedVersion;
    }

    public String getIosStoreUrl() {
        return iosStoreUrl;
    }

    public String getAndroidStoreUrl() {
        return androidStoreUrl;
    }

    public Instant getUpdatedAt() {
        return updatedAt;
    }

    public Long getUpdatedBy() {
        return updatedBy;
    }

    /** 改值（校验在 {@code AppVersionConfigService}，本方法只改值）。无变更也照写 updatedAt / updatedBy。 */
    public void apply(String iosLatestVersion, String androidLatestVersion, String minSupportedVersion,
            String iosStoreUrl, String androidStoreUrl, Instant updatedAt, long updatedBy) {
        this.iosLatestVersion = iosLatestVersion;
        this.androidLatestVersion = androidLatestVersion;
        this.minSupportedVersion = minSupportedVersion;
        this.iosStoreUrl = iosStoreUrl;
        this.androidStoreUrl = androidStoreUrl;
        this.updatedAt = updatedAt;
        this.updatedBy = updatedBy;
    }
}
