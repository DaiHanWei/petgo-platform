package com.tailtopia.config.service;

import com.tailtopia.config.domain.AppVersionConfig;
import com.tailtopia.config.repository.AppVersionConfigRepository;
import com.tailtopia.shared.error.AppException;
import java.time.Instant;
import java.util.Optional;
import java.util.regex.Pattern;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * App 版本配置服务（V1.3.4 Story 3.1 / AD-10）。
 *
 * <p>读：{@link #current()} 每次读库，<b>不加任何缓存</b>（架构护栏禁通用缓存层；改值后下一次请求即生效）。
 * <p>写：{@link #update} 供 admin 主题保存接口调用（AD-1），校验顺序与错误码写死（D-17）。
 * 本 story 不写 {@code config_change_logs}、不调审计（留痕属 admin 主题）。
 *
 * <p>放 {@code com.tailtopia.config} 包而非 admin 包：{@code AdminMessagesExternalizedTest} 只扫
 * admin 包里的 {@code admin.err.*} 字面量并要求三语齐全，而本 story 不加文案（D-17）。
 */
@Service
public class AppVersionConfigService {

    /** 严格 x.y.z：每段 0~9999、无前导零、无 v 前缀、无后缀。 */
    private static final Pattern VERSION =
            Pattern.compile("^(0|[1-9]\\d{0,3})\\.(0|[1-9]\\d{0,3})\\.(0|[1-9]\\d{0,3})$");

    private static final String BASELINE_MIN = "1.0.0";

    private final AppVersionConfigRepository repository;

    public AppVersionConfigService(AppVersionConfigRepository repository) {
        this.repository = repository;
    }

    /** 当前配置（id=1）；行缺失返回 empty，由调用方决定如何失败。 */
    @Transactional(readOnly = true)
    public Optional<AppVersionConfig> current() {
        return repository.findById(AppVersionConfig.SINGLETON_ID);
    }

    @Transactional
    public AppVersionConfig update(AppVersionConfigUpdate cmd, long adminId) {
        AppVersionConfigUpdate v = validate(cmd);
        AppVersionConfig cfg = repository.findById(AppVersionConfig.SINGLETON_ID)
                .orElseThrow(() -> new IllegalStateException("app_version_config 缺失"));
        cfg.apply(v.iosLatestVersion(), v.androidLatestVersion(), v.minSupportedVersion(), v.iosStoreUrl(),
                v.androidStoreUrl(), Instant.now(), adminId);
        return repository.save(cfg);
    }

    /**
     * 只校验、不写库（V1.3.4 后台返工清单 §2：保存前的确认弹层先走这里，校验不过不弹层）。
     * 校验顺序与错误码是 {@link #update} 的唯一口径：格式 → 链接 scheme → 最低版本 ≤ iOS 最新 → ≤ Android 最新 → 上调须两链接齐。
     *
     * @return trim 后的值（与落库口径一致）
     */
    public AppVersionConfigUpdate validate(AppVersionConfigUpdate cmd) {
        String ios = trim(cmd.iosLatestVersion());
        String android = trim(cmd.androidLatestVersion());
        String min = trim(cmd.minSupportedVersion());
        String iosUrl = trim(cmd.iosStoreUrl());
        String androidUrl = trim(cmd.androidStoreUrl());

        require(VERSION.matcher(ios).matches()
                        && VERSION.matcher(android).matches()
                        && VERSION.matcher(min).matches(),
                "版本号格式应为 x.y.z", "admin.err.config.version.format");
        require(httpsOrBlank(iosUrl) && httpsOrBlank(androidUrl),
                "商店链接须以 https:// 开头", "admin.err.config.store-url.scheme");
        // 拆 iOS / Android 两条并带出该端最新版本号（UI 稿 G5）：运营一眼看出是哪一端挡住了
        require(compare(min, ios) <= 0,
                "最低支持版本不能高于 iOS 最新版本号（" + ios + "）", "admin.err.config.version.min-gt-ios-latest", ios);
        require(compare(min, android) <= 0,
                "最低支持版本不能高于 Android 最新版本号（" + android + "）",
                "admin.err.config.version.min-gt-android-latest", android);
        require(!(compare(min, BASELINE_MIN) > 0 && (iosUrl.isEmpty() || androidUrl.isEmpty())),
                "上调最低支持版本前，须先填写 iOS 与 Android 商店链接", "admin.err.config.store-url.blank");
        return new AppVersionConfigUpdate(ios, android, min, iosUrl, androidUrl);
    }

    /** 两个版本号按数值逐段比较（入参须已通过格式校验）；给后台确认弹层判断上调 / 下调用。 */
    public static int compareVersions(String a, String b) {
        return compare(a, b);
    }

    private static String trim(String s) {
        return s == null ? "" : s.trim();
    }

    private static boolean httpsOrBlank(String url) {
        return url.isEmpty() || url.startsWith("https://");
    }

    /** 按数值逐段比较（只在格式校验通过后调用，入参保证为三段纯数字）。 */
    private static int compare(String a, String b) {
        String[] pa = a.split("\\.");
        String[] pb = b.split("\\.");
        for (int i = 0; i < 3; i++) {
            int c = Integer.compare(Integer.parseInt(pa[i]), Integer.parseInt(pb[i]));
            if (c != 0) {
                return c;
            }
        }
        return 0;
    }

    /** 校验失败即抛（中文原文 + 后台本地化文案码）。照抄 {@code AdminConfigService.require}。 */
    private static void require(boolean cond, String msg, String code, Object... args) {
        if (!cond) {
            throw AppException.validation(msg).code(code, args);
        }
    }
}
