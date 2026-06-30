package com.tailtopia.shared.web;

import org.springframework.core.io.ClassPathResource;
import org.springframework.core.io.Resource;
import org.springframework.http.CacheControl;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

import java.time.Duration;

/**
 * 法律政策对外 H5（公开无需鉴权）：
 * <ul>
 *   <li>{@code GET /privacy} → 隐私政策（Kebijakan Privasi）</li>
 *   <li>{@code GET /terms} → 服务条款（Syarat &amp; Ketentuan）</li>
 *   <li>{@code GET /account-deletion} → 账号删除说明（Google Play 数据删除 URL 要求）</li>
 *   <li>{@code GET /child-safety} → 儿童安全标准（Google Play CSAE 标准 URL 要求）</li>
 *   <li>{@code GET /support} → 支持/帮助页（App Store Support URL 必填项要求）</li>
 * </ul>
 *
 * <p>内容为运营定稿的静态 H5（{@code classpath:/legal/*.html}），自包含内联样式，无外部资源依赖，
 * 故对外只需放行这两条路径（见 SecurityConfig）。供 App 内 WebView / 应用商店上架隐私政策 URL 引用。
 * 公网经 Cloudflare Tunnel 把根域名的 {@code /privacy}、{@code /terms} 映射到本服务（127.0.0.1:8084）。
 */
@RestController
public class LegalPageController {

    private static final MediaType HTML_UTF8 = new MediaType("text", "html", java.nio.charset.StandardCharsets.UTF_8);

    @GetMapping("/privacy")
    public ResponseEntity<Resource> privacy() {
        return html("legal/privacy.html");
    }

    @GetMapping("/terms")
    public ResponseEntity<Resource> terms() {
        return html("legal/terms.html");
    }

    @GetMapping("/account-deletion")
    public ResponseEntity<Resource> accountDeletion() {
        return html("legal/account-deletion.html");
    }

    @GetMapping("/child-safety")
    public ResponseEntity<Resource> childSafety() {
        return html("legal/child-safety.html");
    }

    @GetMapping("/support")
    public ResponseEntity<Resource> support() {
        return html("legal/support.html");
    }

    private ResponseEntity<Resource> html(String classpathLocation) {
        Resource body = new ClassPathResource(classpathLocation);
        return ResponseEntity.ok()
                .contentType(HTML_UTF8)
                // 政策文本变动不频繁；允许公网缓存 1 小时，减轻源站压力。
                .cacheControl(CacheControl.maxAge(Duration.ofHours(1)).cachePublic())
                .body(body);
    }
}
