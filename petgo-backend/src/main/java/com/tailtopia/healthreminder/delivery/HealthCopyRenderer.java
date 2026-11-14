package com.tailtopia.healthreminder.delivery;

import com.tailtopia.healthreminder.domain.HealthNotification;
import com.tailtopia.healthreminder.domain.HealthReminder;
import com.tailtopia.healthreminder.domain.ReminderFamily;
import com.tailtopia.healthreminder.domain.ReminderKind;
import com.tailtopia.healthreminder.domain.ReminderOrigin;
import com.tailtopia.healthreminder.domain.ReminderRoute;
import com.tailtopia.healthreminder.rules.CheckupDueRule;
import com.tailtopia.healthreminder.schedule.SendGroup;
import com.tailtopia.profile.domain.DewormType;
import com.tailtopia.profile.domain.PetSex;
import com.tailtopia.profile.domain.VaccinePreset;
import com.tailtopia.profile.health.HealthDates;
import com.tailtopia.profile.health.PetHealthSnapshot;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.Set;
import java.util.function.Function;
import org.springframework.context.MessageSource;
import org.springframework.stereotype.Component;

/**
 * 健康提醒文案（V1.5.0 Story 2.4，PRD 附录 A / C、FR-166 Same-type merge copy、FR-169，架构增量 §5.3）。
 * i18n 键 {@code notify.health.<ROW>.title/body}；语言 = 收件人 locale（en → EN，其余 → ID）。
 *
 * <p>选行：Rabies D-7（单条 Rabies）、Series D-Day（单条且有到期剂次）、Internal Young（到期日早于满 6 个月，猫狗）、
 * D+3 external 狗变体；合并：疫苗一律 generic，驱虫含 External / Flea+worm → Flea+worm 文案。
 * 占位符缺值时用不含它的变体。🔒 文案只进推送与通知中心，不进日志。
 */
@Component
public class HealthCopyRenderer {

    private static final int MAX_VACCINE = 30;
    private static final int MAX_JUDUL = 20;
    private static final int WEEKS_LIMIT_DAYS = 56;

    private final MessageSource messages;

    public HealthCopyRenderer(MessageSource messages) {
        this.messages = messages;
    }

    /**
     * @param petName       宠物名（可空 → hewanmu / your pet）
     * @param sourceEventDate 来源记录 id → event date（{@code {interval}} 用；查不到返回 null）
     */
    public RenderedCopy render(SendGroup group, PetHealthSnapshot pet, String petName, Locale locale,
            Function<Long, LocalDate> sourceEventDate) {
        List<HealthReminder> rows = group.rows();
        LocalDate send = group.day();
        boolean en = locale != null && "en".equalsIgnoreCase(locale.getLanguage());
        String nama = clean(petName, 20);
        if (nama == null) {
            nama = en ? "your pet" : "hewanmu";
        }
        ReminderKind kind = notificationKind(rows);
        HealthNotification.Route route = rows.size() > 1 || kind.route() == ReminderRoute.PAGE
                ? HealthNotification.Route.PAGE : HealthNotification.Route.FORM;
        Copy c = choose(kind, rows, pet, send, sourceEventDate);
        Object[] args = new Object[c.args().length + 1];
        args[0] = nama;
        System.arraycopy(c.args(), 0, args, 1, c.args().length);
        // notifications.title / body 列上限 120 / 255（多个自定义名合并时可能超长）。
        String title = cap(tidy(messages.getMessage("notify.health." + c.row() + ".title", args, locale)), 120);
        String body = cap(tidy(messages.getMessage("notify.health." + c.row() + ".body", args, locale)), 255);
        return new RenderedCopy(title, body, kind, route);
    }

    /** 通知用的 kind（PRD §4 notification_kind）：单条 = 自身；驱虫合并按是否含 External / Flea+worm 选。 */
    public static ReminderKind notificationKind(List<HealthReminder> rows) {
        ReminderKind first = rows.get(0).getReminderKind();
        if (rows.size() == 1 || first.family() != ReminderFamily.DEWORM) {
            return first;
        }
        boolean external = rows.stream().anyMatch(r -> isExternalish(r));
        boolean dDay = rows.get(0).getPushPoint() == com.tailtopia.healthreminder.domain.PushPoint.D_DAY;
        if (dDay) {
            return external ? ReminderKind.DEWORM_FLEA_WORM_D_DAY : ReminderKind.DEWORM_INTERNAL_D_DAY;
        }
        return external ? ReminderKind.DEWORM_EXTERNAL_D_PLUS_3 : ReminderKind.DEWORM_INTERNAL_D_PLUS_3;
    }

    private static boolean isExternalish(HealthReminder r) {
        return r.getDewormType() == DewormType.EXTERNAL || r.getDewormType() == DewormType.FLEA_WORM;
    }

    /** 一行文案 + 除宠物名外的参数（{1} 起）。 */
    private record Copy(String row, Object... args) {
    }

    private Copy choose(ReminderKind kind, List<HealthReminder> rows, PetHealthSnapshot pet, LocalDate send,
            Function<Long, LocalDate> sourceEventDate) {
        boolean merged = rows.size() > 1;
        HealthReminder one = rows.get(0);
        switch (kind) {
            case VACCINE_D_MINUS_7 -> {
                long hariLagi = rows.stream().filter(r -> r.getNextDateRef() != null)
                        .mapToLong(r -> ChronoUnit.DAYS.between(send, r.getNextDateRef())).min().orElse(7);
                if (!merged && one.getDueVaccinePreset() == VaccinePreset.RABIES) {
                    return new Copy("vaccineDMinus7Rabies", hariLagi);
                }
                String vaksin = vaccineNames(rows);
                return new Copy("vaccineDMinus7", hariLagi, vaksin == null ? "" : vaksin);
            }
            case VACCINE_D_DAY -> {
                if (!merged && one.getDueDose() != null) {
                    return new Copy("vaccineDDaySeries", one.getDueDose());
                }
                return new Copy("vaccineDDay");
            }
            case VACCINE_D_PLUS_7 -> {
                long hari = rows.stream().filter(r -> r.getNextDateRef() != null)
                        .mapToLong(r -> ChronoUnit.DAYS.between(r.getNextDateRef(), send)).max().orElse(7);
                return new Copy("vaccineDPlus7", hari);
            }
            case FIRST_VACCINE -> {
                return new Copy("firstVaccine");
            }
            case FIRST_DEWORM -> {
                return new Copy("firstDeworm");
            }
            case DEWORM_INTERNAL_D_DAY, DEWORM_LEGACY_D_DAY -> {
                boolean legacyOnly = rows.stream().allMatch(r -> r.getReminderKind() == ReminderKind.DEWORM_LEGACY_D_DAY);
                if (!legacyOnly && youngAtDue(rows, pet)) {
                    return new Copy("dewormInternalDDayYoung", pet.isCat() ? 0 : 1);
                }
                Long days = interval(rows, send, sourceEventDate);
                if (days == null) {
                    return new Copy("firstDeworm"); // 缺 {interval}：用不含它的驱虫文案
                }
                Object[] iv = intervalArgs(days);
                return new Copy(legacyOnly ? "dewormLegacyDDay" : "dewormInternalDDay", iv[0], iv[1]);
            }
            case DEWORM_EXTERNAL_D_DAY -> {
                return new Copy("dewormExternalDDay");
            }
            case DEWORM_FLEA_WORM_D_DAY -> {
                return new Copy("dewormFleaWormDDay");
            }
            case DEWORM_HEARTWORM_D_DAY -> {
                return new Copy("dewormHeartwormDDay");
            }
            case DEWORM_INTERNAL_D_PLUS_3, DEWORM_LEGACY_D_PLUS_3 -> {
                return new Copy("dewormDPlus3Internal");
            }
            case DEWORM_EXTERNAL_D_PLUS_3 -> {
                return new Copy(pet.isDog() ? "dewormDPlus3ExternalDog" : "dewormDPlus3External");
            }
            case SPAY_NEUTER_SUGGESTION -> {
                return new Copy(pet.sex() == PetSex.MALE ? "neuterMale" : "spayFemale");
            }
            case STITCH_CHECK -> {
                return new Copy("stitchCheck");
            }
            case HEAT_DOG_D_MINUS_7 -> {
                return new Copy("heatDogDMinus7");
            }
            case PYOMETRA_WATCH -> {
                return new Copy("pyometraWatch");
            }
            case HEAT_CAT_SECOND -> {
                return new Copy("heatCatSecond");
            }
            case HEAT_MALE_FIRST -> {
                return new Copy("heatMaleFirst");
            }
            case CHECKUP -> {
                LocalDate due = one.getOrigin() == ReminderOrigin.LAUNCH_QUEUE ? send : one.getDueDate();
                boolean senior = pet.birthday() != null && pet.isCatOrDog()
                        && !due.isBefore(CheckupDueRule.seniorBirthday(pet.petType(), pet.birthday()));
                return new Copy(senior ? "checkupSenior" : "checkupAdult");
            }
            case VET_VISIT_D_MINUS_1 -> {
                return new Copy("vetVisitDMinus1");
            }
            case VET_VISIT_D_DAY -> {
                return new Copy("vetVisitDDay");
            }
            case CUSTOM_D_DAY -> {
                Set<String> names = new LinkedHashSet<>();
                rows.forEach(r -> {
                    String n = clean(r.getCustomName(), MAX_JUDUL);
                    if (n != null) {
                        names.add(n);
                    }
                });
                return new Copy("customDDay", String.join(" & ", names));
            }
            default -> throw new IllegalStateException("unmapped kind " + kind);
        }
    }

    /** 驱虫到期日早于满 6 个月（猫狗才有幼崽文案）。 */
    private static boolean youngAtDue(List<HealthReminder> rows, PetHealthSnapshot pet) {
        if (!pet.isCatOrDog() || pet.birthday() == null) {
            return false;
        }
        LocalDate due = rows.stream().map(r -> r.getNextDateRef() != null ? r.getNextDateRef() : r.getDueDate())
                .min(LocalDate::compareTo).orElse(null);
        return due != null && HealthDates.isYoung(pet.birthday(), due);
    }

    /** 来源记录 event date → 发送日的天数，合并取最大；都查不到 → null。 */
    private static Long interval(List<HealthReminder> rows, LocalDate send, Function<Long, LocalDate> eventDate) {
        return rows.stream().map(HealthReminder::getSourceRecordId).filter(Objects::nonNull).map(eventDate)
                .filter(Objects::nonNull).map(d -> ChronoUnit.DAYS.between(d, send)).max(Long::compare).orElse(null);
    }

    /**
     * {@code {interval}}（FR-169）：&lt;8 周按整周（向下取整、最小 1），否则按四舍五入整月。返回 [数字, 单位码]，单位码：
     * 0 周单数 / 1 周复数 / 2 月单数 / 3 月复数（EN 用它选单复数，ID 不区分）。
     */
    static Object[] intervalArgs(long days) {
        if (days < WEEKS_LIMIT_DAYS) {
            long weeks = Math.max(1, days / 7);
            return new Object[] {weeks, weeks == 1 ? 0 : 1};
        }
        long months = Math.max(1, Math.round(days / 30.4375));
        return new Object[] {months, months == 1 ? 2 : 3};
    }

    /** 合并疫苗名（预设标签或原文），「 & 」连接；都没有 → null。 */
    private static String vaccineNames(List<HealthReminder> rows) {
        Set<String> names = new LinkedHashSet<>();
        for (HealthReminder r : rows) {
            String n = r.getDueVaccinePreset() != null && r.getDueVaccinePreset().label() != null
                    ? r.getDueVaccinePreset().label() : clean(r.getDueVaccineName(), MAX_VACCINE);
            if (n != null) {
                names.add(n);
            }
        }
        return names.isEmpty() ? null : String.join(" & ", names);
    }

    /** 用户文本去换行与控制字符、保持字段上限；空 → null。 */
    static String clean(String s, int max) {
        if (s == null) {
            return null;
        }
        String t = s.replaceAll("[\\p{Cntrl}\\u2028\\u2029]+", " ").trim();
        if (t.isEmpty()) {
            return null;
        }
        return t.length() <= max ? t : cut(t, max).trim();
    }

    private static String cap(String s, int max) {
        return s.length() <= max ? s : cut(s, max - 1) + "\u2026";
    }

    /** 按码点截断到不超过 {@code max} 个 UTF-16 单元，不切断 emoji 等代理对。 */
    private static String cut(String s, int max) {
        int end = max;
        if (end > 0 && end < s.length() && Character.isHighSurrogate(s.charAt(end - 1))) {
            end--;
        }
        return s.substring(0, end);
    }

    /** 缺值时留下的连续空格收成一个。 */
    private static String tidy(String s) {
        return s.replaceAll(" {2,}", " ").trim();
    }
}
