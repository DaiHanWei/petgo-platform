package com.tailtopia.shop.coupon.service;

import com.tailtopia.shared.schedule.ScheduleWindow;
import java.time.Instant;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.Locale;
import org.springframework.context.MessageSource;
import org.springframework.stereotype.Component;

/**
 * 券快过期提醒文案（v1.4.2 shop Story 2.3 AC4 · PRD §6 G9）：键 {@code notify.COUPON_EXPIRING.title/body}，
 * 按收件人 locale 渲染。落库值与推送（{@code NotificationService.sendWithCopy}）用同一键、同一 args，两边同一句。
 *
 * <ul>
 *   <li>{@code {0}} = 本次提醒的券张数，传<b>字符串</b>（避免 {@code MessageFormat} 对数字加千分位）；</li>
 *   <li>{@code {1}} = 最早到期那张的到期时刻，按 {@link ScheduleWindow#WIB} 换算、{@code d MMM, HH:mm}、收件人 locale
 *       （id / en「6 Nov, 23:59」），预格式化为字符串再传（照 {@code PetSlotExpiryCopy}）。</li>
 * </ul>
 */
@Component
public class CouponExpiryCopy {

    /** 文案键 / 推送 copyKey（与通知类型同名）。 */
    public static final String COPY_KEY = "COUPON_EXPIRING";

    private final MessageSource messages;

    public CouponExpiryCopy(MessageSource messages) {
        this.messages = messages;
    }

    /** 时刻 → WIB 自然日（提醒日志的「同人同日」键）。 */
    public static LocalDate wibDate(Instant at) {
        return at.atZone(ScheduleWindow.WIB).toLocalDate();
    }

    /** 到期时刻按 WIB、{@code d MMM, HH:mm}、收件人 locale 格式化。 */
    public static String formatExpiry(Instant expiresAt, Locale locale) {
        return DateTimeFormatter.ofPattern("d MMM, HH:mm", locale).withZone(ScheduleWindow.WIB).format(expiresAt);
    }

    /** {@code {"<张数>", "<最早到期 WIB>"}}。 */
    public Object[] args(int couponCount, Instant earliestExpiry, Locale locale) {
        return new Object[] {String.valueOf(couponCount), formatExpiry(earliestExpiry, locale)};
    }

    /** 渲染 {@code notify.COUPON_EXPIRING.<suffix>}（{@code suffix} = title / body）。 */
    public String render(String suffix, Object[] args, Locale locale) {
        return messages.getMessage("notify." + COPY_KEY + "." + suffix, args, locale);
    }
}
