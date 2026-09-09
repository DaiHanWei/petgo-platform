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
}
