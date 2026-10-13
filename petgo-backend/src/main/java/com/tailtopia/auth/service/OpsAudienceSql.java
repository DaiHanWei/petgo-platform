package com.tailtopia.auth.service;

import com.tailtopia.notify.ops.domain.AudienceSpec;
import com.tailtopia.profile.domain.PetType;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 运营通知人群 SQL 生成（V1.3.4 Story 6.2 · AC1 · D-16）。<b>纯函数</b>，不碰库，L0 可测；
 * {@link OpsAudienceQuery} 只负责执行。
 *
 * <h2>基础过滤（任何模式都加）</h2>
 * 与 {@code UserRepository.findLifecyclePushCandidates} 逐字同口径：普通用户、真实账号、未注销、未停用、
 * 状态 ACTIVE——封号 / 注销 / 停用 / 虚拟号 / 兽医 / 管理员一律排除（FR-138.5）。
 *
 * <h2>FILTER</h2>
 * 各条件之间为「且」；键缺失 = 该条不筛；日期按 WIB 自然日含两端
 * （{@code from} → {@code >= from 00:00 WIB}，{@code to} → {@code < (to + 1 天) 00:00 WIB}），在此换算成
 * {@link Instant} 绑参。给了活跃时间条件时 {@code last_active_at IS NULL} 的用户不命中（比较本身即排除 NULL）。
 *
 * <h2>IDS</h2>
 * 只取子表 {@code ops_notification_campaign_targets} 里的 id + 基础过滤；其余筛选键忽略
 * （由 {@link AudienceSpec} 在 IDS 模式下解析 / 构造时丢弃；本类在 IDS 分支只拼子表条件）。
 */
public final class OpsAudienceSql {

    /** 生成结果：带命名参数的 SQL 与参数表（日期参数为 {@link Instant}，执行侧换成 timestamptz 绑定）。 */
    public record Query(String sql, Map<String, Object> params) {
    }

    /** 基础过滤（与 findLifecyclePushCandidates 同口径）。 */
    static final String BASE_FILTER = "u.role = 'USER' AND u.account_type = 'REAL' AND u.deleted_at IS NULL "
            + "AND u.enabled = TRUE AND u.status = 'ACTIVE'";

    /**
     * 「下过电商订单」= 有过<b>已支付</b>的商城订单（D-16）：{@code PENDING_SHIPMENT} 起均为已付；
     * {@code PENDING_PAYMENT}（未付）与 {@code CANCELLED}（表上无 paid_at、无法区分未付超时与已付取消，取保守口径）不算。
     */
    static final List<String> PAID_ORDER_STATUSES = List.of(
            "PENDING_SHIPMENT", "SHIPPED", "DELIVERED", "COMPLETED", "REFUNDING", "REFUNDED");

    private static final String SELECT = "SELECT u.id, u.locale FROM users u WHERE ";
    private static final String ORDER = " ORDER BY u.id ASC";

    private static final String HAS_PET = "EXISTS (SELECT 1 FROM pet_profiles p WHERE p.owner_id = u.id)";
    private static final String HAS_SPECIES =
            "EXISTS (SELECT 1 FROM pet_profiles p WHERE p.owner_id = u.id AND p.pet_type IN (:species))";
    private static final String HAS_PAID_ORDER =
            "EXISTS (SELECT 1 FROM shop_orders o WHERE o.user_id = u.id AND o.status IN ("
                    + "'" + String.join("','", PAID_ORDER_STATUSES) + "'))";
    private static final String HAS_PHONE = "(u.phone IS NOT NULL AND btrim(u.phone) <> '')";
    private static final String NO_PHONE = "(u.phone IS NULL OR btrim(u.phone) = '')";
    private static final String IN_TARGETS = "u.id IN (SELECT t.user_id FROM ops_notification_campaign_targets t "
            + "WHERE t.campaign_id = :campaignId)";

    private OpsAudienceSql() {
    }

    public static Query build(AudienceSpec spec, long campaignId, ZoneId wib) {
        StringBuilder where = new StringBuilder(BASE_FILTER);
        Map<String, Object> params = new LinkedHashMap<>();
        switch (spec.mode()) {
            case ALL -> {
                // 只有基础过滤
            }
            case IDS -> {
                where.append(" AND ").append(IN_TARGETS);
                params.put("campaignId", campaignId);
            }
            case FILTER -> appendFilter(spec, wib, where, params);
        }
        return new Query(SELECT + where + ORDER, Collections.unmodifiableMap(params));
    }

    private static void appendFilter(AudienceSpec spec, ZoneId wib, StringBuilder where,
            Map<String, Object> params) {
        if (spec.registeredFrom() != null) {
            where.append(" AND u.created_at >= :regFrom");
            params.put("regFrom", startOf(spec.registeredFrom(), wib));
        }
        if (spec.registeredTo() != null) {
            where.append(" AND u.created_at < :regToExcl");
            params.put("regToExcl", startOf(spec.registeredTo().plusDays(1), wib));
        }
        if (spec.activeFrom() != null) {
            where.append(" AND u.last_active_at >= :actFrom");
            params.put("actFrom", startOf(spec.activeFrom(), wib));
        }
        if (spec.activeTo() != null) {
            where.append(" AND u.last_active_at < :actToExcl");
            params.put("actToExcl", startOf(spec.activeTo().plusDays(1), wib));
        }
        if (spec.hasPet() != null) {
            where.append(spec.hasPet() ? " AND " : " AND NOT ").append(HAS_PET);
        }
        if (spec.species() != null && !spec.species().isEmpty()) {
            where.append(" AND ").append(HAS_SPECIES);
            params.put("species", spec.species().stream().map(PetType::name).sorted().toList());
        }
        if (spec.hasShopOrder() != null) {
            where.append(spec.hasShopOrder() ? " AND " : " AND NOT ").append(HAS_PAID_ORDER);
        }
        if (spec.hasPhone() != null) {
            where.append(" AND ").append(spec.hasPhone() ? HAS_PHONE : NO_PHONE);
        }
    }

    private static Instant startOf(LocalDate day, ZoneId wib) {
        return day.atStartOfDay(wib).toInstant();
    }
}
