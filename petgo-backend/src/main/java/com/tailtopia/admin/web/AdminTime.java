package com.tailtopia.admin.web;

import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import org.springframework.stereotype.Component;

/**
 * 后台模板时间统一显示印尼时间（WIB，Asia/Jakarta，UTC+7；bug 20260720-314）。
 *
 * <p>Thymeleaf 用 {@code ${@adminTime.wib(instant)}} 调用；空值回 "—"，与既有 {@code ?: '—'} 一致。
 * 复用于 AI/兽医订单列表与详情、阶段时间线；后续后台时间显示统一走此 bean（避免各模板直出 UTC）。
 */
@Component("adminTime")
public class AdminTime {

    private static final DateTimeFormatter WIB_FMT =
            DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss").withZone(ZoneId.of("Asia/Jakarta"));

    /** Instant → "yyyy-MM-dd HH:mm:ss WIB"；null → "—"。 */
    public String wib(Instant t) {
        return t == null ? "—" : WIB_FMT.format(t) + " WIB";
    }

    /**
     * 「此刻的 WIB 时间」，供 {@code datetime-local} 输入框旁边做参照（bug 20260828）。
     *
     * <p>🔴 后台所有 {@code datetime-local} 都按 WIB 解释，而运营在中国（UTC+8）——
     * 照着自己的表填「现在」，落到 WIB 就是**一小时后**，标签当场看起来「没生效」。
     * 实机上这已经导致过一次「配了标签用户端不显示」的误判。
     * 光写「WIB」三个字母不解决问题：那要求运营心算时差。直接把此刻的 WIB 摆出来。
     *
     * <p>格式与输入框一致（{@code yyyy-MM-ddTHH:mm}），方便直接照抄。
     */
    public String nowWibForInput() {
        return java.time.LocalDateTime.now(ZoneId.of("Asia/Jakarta"))
                .format(java.time.format.DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm"));
    }
}
