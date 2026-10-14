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
        String ios = trim(cmd.iosLatestVersion());
        String android = trim(cmd.androidLatestVersion());
        String min = trim(cmd.minSupportedVersion());
        String iosUrl = trim(cmd.iosStoreUrl());
        String androidUrl = trim(cmd.androidStoreUrl());

        require(VERSION.matcher(ios).matches()
                        && VERSION.matcher(android).matches()
                        && VERSION.matcher(min).matches(),
                "版本号格式不正确，应为 x.y.z", "admin.err.config.version.format");
        require(httpsOrBlank(iosUrl) && httpsOrBlank(androidUrl),
                "商店链接必须以 https:// 开头", "admin.err.config.store-url.scheme");
        require(compare(min, ios) <= 0 && compare(min, android) <= 0,
                "最低支持版本不能高于任一端最新版本", "admin.err.config.version.min-gt-latest");
        require(!(compare(min, BASELINE_MIN) > 0 && (iosUrl.isEmpty() || androidUrl.isEmpty())),
                "上调最低支持版本前，两个商店链接都必须填写", "admin.err.config.store-url.blank");

        AppVersionConfig cfg = repository.findById(AppVersionConfig.SINGLETON_ID)
                .orElseThrow(() -> new IllegalStateException("app_version_config 缺失"));
        cfg.apply(ios, android, min, iosUrl, androidUrl, Instant.now(), adminId);
        return repository.save(cfg);
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
