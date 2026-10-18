package com.tailtopia.profile.slot;

import com.tailtopia.shared.schedule.ScheduleWindow;
import java.time.Instant;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Locale;
import org.springframework.context.MessageSource;
import org.springframework.stereotype.Component;

/**
 * 名额到期两类通知的文案（V1.4.0 multi-pet Story 2.3 · K1 / 对照表 K-1 ~ K-4）：键 {@code notify.<copyKey>.title/body}，
 * 按收件人 locale 渲染。落库值与推送（{@code NotificationService.sendWithCopy}）用同一键、同一 args，两边同一句。
 *
 * <ul>
 *   <li>{@code PET_SLOT_EXPIRING}：{@code {0}} = 到期日，按 {@link ScheduleWindow#WIB} 换算、{@code d MMM}、收件人 locale
 *       （id「16 Okt」/ en「16 Oct」），预格式化为字符串再传参（不让 {@code MessageFormat} 按 JVM 时区格式化日期）；</li>
 *   <li>{@code PET_SLOT_EXPIRED}：{@code {0}} = 被转只读的宠物名（{@link PetNameJoiner}）。</li>
 * </ul>
 */
@Component
public class PetSlotExpiryCopy {

    static final String EXPIRING = "PET_SLOT_EXPIRING";
    static final String EXPIRED = "PET_SLOT_EXPIRED";

    private final MessageSource messages;

    public PetSlotExpiryCopy(MessageSource messages) {
        this.messages = messages;
    }

    /** 到期时刻 → WIB 自然日（同日合并提醒的键）。 */
    public static LocalDate wibDate(Instant expiresAt) {
        return expiresAt.atZone(ScheduleWindow.WIB).toLocalDate();
    }

    /** {@code d MMM}，按收件人 locale（id「16 Okt」/ en「16 Oct」）。 */
    public static String formatDate(LocalDate date, Locale locale) {
        return DateTimeFormatter.ofPattern("d MMM", locale).format(date);
    }

    public Object[] expiringArgs(LocalDate wibDate, Locale locale) {
        return new Object[] {formatDate(wibDate, locale)};
    }

    public Object[] expiredArgs(List<String> petNames) {
        return new Object[] {PetNameJoiner.join(petNames)};
    }

    /** 渲染 {@code notify.<copyKey>.<suffix>}（{@code suffix} = title / body）。 */
    public String render(String copyKey, String suffix, Object[] args, Locale locale) {
        return messages.getMessage("notify." + copyKey + "." + suffix, args, locale);
    }
}
