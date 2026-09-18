package com.tailtopia.admin.warmreply.dto;

import com.tailtopia.shared.error.AppException;
import com.tailtopia.shared.schedule.ScheduleWindow;
import java.time.LocalDate;
import java.util.List;
import java.util.Set;

/**
 * 帖子评论分布页签的筛选条件（V1.3.0 Story 4.1 AC2）。
 *
 * @param count          评论数区间：{@code all} / {@code zero}（=0）/ {@code le3}（≤3）/ {@code custom}（≤N）
 * @param n              自定义 N（仅 custom；正整数）
 * @param from           发布时间段起（WIB 自然日，含），默认近 7 天
 * @param to             发布时间段止（WIB 自然日，含），默认昨天 … 今天
 * @param species        物种：null = 全部；CAT / DOG / OTHER / GENERAL
 * @param status         帖子状态：{@code all}（默认）/ {@code visible} / {@code takendown}
 * @param excludeVirtual 「排除虚拟账号内容」（默认开；判定 = 作者 {@code account_type = 'VIRTUAL'}，真实身份池照常参与）
 * @param page           0 起
 */
public record DistributionFilter(String count, Integer n, LocalDate from, LocalDate to, String species, String status,
        boolean excludeVirtual, int page) {

    public static final int PAGE_SIZE = 20;
    public static final List<String> COUNTS = List.of("all", "zero", "le3", "custom");
    public static final List<String> SPECIES = List.of("CAT", "DOG", "OTHER", "GENERAL");
    public static final List<String> STATUSES = List.of("all", "visible", "takendown");
    public static final Set<String> ALLOWED_SPECIES = Set.copyOf(SPECIES);

    /** 从请求参数规范化：非法枚举回默认；自定义 N 缺失 / 非正整数 → 422（{@code admin.err.comments.distribution.badCount}）。 */
    public static DistributionFilter of(String count, Integer n, LocalDate from, LocalDate to, String species, String status,
            Boolean excludeVirtual, Integer page) {
        String c = count == null || !COUNTS.contains(count) ? "all" : count;
        Integer nn = null;
        if ("custom".equals(c)) {
            if (n == null || n < 1) {
                throw AppException.validation("自定义评论数上限必须是正整数").code("admin.err.comments.distribution.badCount");
            }
            nn = n;
        }
        LocalDate today = LocalDate.now(ScheduleWindow.WIB);
        LocalDate t = to == null ? today : to;
        LocalDate f = from == null ? t.minusDays(6) : from;
        if (f.isAfter(t)) {
            LocalDate x = f;
            f = t;
            t = x;
        }
        String sp = species == null || species.isBlank() || !ALLOWED_SPECIES.contains(species) ? null : species;
        String st = status == null || !STATUSES.contains(status) ? "all" : status;
        return new DistributionFilter(c, nn, f, t, sp, st, excludeVirtual == null || excludeVirtual, page == null ? 0 : Math.max(page, 0));
    }

    /** 评论数上限（含）；null = 不限。 */
    public Integer maxCount() {
        return switch (count) {
            case "zero" -> 0;
            case "le3" -> 3;
            case "custom" -> n;
            default -> null;
        };
    }

    public int offset() {
        return page * PAGE_SIZE;
    }
}
