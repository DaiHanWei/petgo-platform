package com.tailtopia.admin.shared.time;

import com.tailtopia.shared.schedule.ScheduleWindow;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import org.springframework.stereotype.Component;

/**
 * 后台时间显示工具（V1.3.0 Story 2.3a AC6，D-22：后台一律 WIB）。Thymeleaf 里 {@code ${@adminTime.fmt(x)}}。
 * 时区常量取 {@link ScheduleWindow#WIB}（单一来源）；既有页面（审计等）改用本工具由各页 story 完成（6.5 / 7.5 / 7.6）。
 */
@Component("adminTime")
public class AdminTime {

    public static final ZoneId ZONE = ScheduleWindow.WIB;
    private static final DateTimeFormatter DATE_TIME = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm").withZone(ZONE);
    private static final DateTimeFormatter DATE = DateTimeFormatter.ofPattern("yyyy-MM-dd").withZone(ZONE);

    /** {@code yyyy-MM-dd HH:mm}（WIB）；null → 空串。 */
    public String fmt(Instant instant) {
        return instant == null ? "" : DATE_TIME.format(instant);
    }

    /** {@code yyyy-MM-dd}（WIB）；null → 空串。 */
    public String fmtDate(Instant instant) {
        return instant == null ? "" : DATE.format(instant);
    }

    /** WIB 今日。 */
    public LocalDate today() {
        return LocalDate.now(ZONE);
    }

    public ZoneId zone() {
        return ZONE;
    }

    // ---- 以下三个方法自原 admin/web/AdminTime（@Component("adminTime"）合并而来（Story 2.4：两个同名 bean 会让上下文启动失败）；
    //      33 个既有模板仍用 ${@adminTime.wib(...)} / nowWibForInput / wibForInput，语义与格式逐字不变。 ----

    private static final DateTimeFormatter WIB_LEGACY = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss").withZone(ZONE);
    private static final DateTimeFormatter INPUT = DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm");

    /** Instant → "yyyy-MM-dd HH:mm:ss WIB"；null → "—"（bug 20260720-314 既有口径）。 */
    public String wib(Instant t) {
        return t == null ? "—" : WIB_LEGACY.format(t) + " WIB";
    }

    /** 「此刻的 WIB 时间」（datetime-local 参照，bug 20260828）。 */
    public String nowWibForInput() {
        return java.time.LocalDateTime.now(ZONE).format(INPUT);
    }

    /** 已存时刻 → datetime-local 回显值（WIB）；null → 空串（bug 20260901-468）。 */
    public String wibForInput(Instant t) {
        return t == null ? "" : java.time.LocalDateTime.ofInstant(t, ZONE).format(INPUT);
    }
}
