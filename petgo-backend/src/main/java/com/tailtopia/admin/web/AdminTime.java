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
}
