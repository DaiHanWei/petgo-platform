package com.tailtopia.admin.web;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.util.unit.DataSize;
import org.springframework.web.bind.annotation.ControllerAdvice;
import org.springframework.web.bind.annotation.ModelAttribute;

/**
 * 把 multipart 上限暴露给后台模板（2026-09-03）。
 *
 * <h2>为什么需要它</h2>
 * 直传控件此前<b>不知道上限是多少</b>，只能把超限的图整个发出去、等服务端拒。而 Tomcat 是在
 * multipart 解析阶段就拒的：那一刻浏览器还在发请求体，连接随即被重置 ——
 * {@code fetch} 既不 resolve 也不 reject，界面就<b>永远停在「正在上传…」</b>
 * （2026-09-03 stag 回归 P1，运营实测等了 4 分钟无任何反馈）。
 *
 * <p>有了这两个值，控件就能在**发出去之前**拦下来，并直接说清「单张不能超过 N MB」。
 *
 * <p>⚠️ 这是<b>体验</b>护栏，不是安全边界 —— 前端校验可被绕过，判定点仍在服务端
 * （{@code spring.servlet.multipart.max-file-size} + 各 Service 里那道 MAX_BYTES）。
 * 两者取同一个配置源，不会漂移。
 */
@ControllerAdvice(basePackages = "com.tailtopia.admin")
public class AdminUploadLimitAdvice {

    private final DataSize maxFileSize;

    public AdminUploadLimitAdvice(
            @Value("${spring.servlet.multipart.max-file-size:1MB}") DataSize maxFileSize) {
        this.maxFileSize = maxFileSize;
    }

    /** 字节数：给 JS 与 {@code file.size} 直接比。 */
    @ModelAttribute("uploadMaxBytes")
    public long uploadMaxBytes() {
        return maxFileSize.toBytes();
    }

    /** MB 数：只用于文案 {@code admin.seed.upload.tooLarge} 的 {0}。 */
    @ModelAttribute("uploadMaxMb")
    public long uploadMaxMb() {
        return maxFileSize.toMegabytes();
    }
}
