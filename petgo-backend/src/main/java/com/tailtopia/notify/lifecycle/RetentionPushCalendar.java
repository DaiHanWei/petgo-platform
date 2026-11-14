package com.tailtopia.notify.lifecycle;

import com.tailtopia.notify.schedule.AnniversaryPlan;
import com.tailtopia.profile.dto.PetProfileSnapshot;
import com.tailtopia.shared.schedule.ScheduleWindow;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.Collection;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Service;

/**
 * 留存推送日历（V1.5.0 地基 Story 0.1）：推送与召回线对健康记录提醒线的两个「提前知道」接口
 * （推送与召回 PRD §6「与健康记录提醒 PRD 的约定」）。
 *
 * <ul>
 *   <li><b>召回日</b>：健康提醒第 8 档建议提醒、上线排队要避开「新用户召回」推送日（只算 D1 / D3 / D7）。</li>
 *   <li><b>纪念日占位</b>：当天会收到纪念日推送的宠物，健康提醒把它算作当天已占用。</li>
 * </ul>
 *
 * <p><b>归属</b>：签名由地基定死，两条线只调用、不改签名、不改包名、不做替身。
 * 方法体归 {@code feat/1.5.0-push-recall}（会话 ③）：召回日已按 PRD 写好保守判定，③ 若改节点规则须同步改这里；
 * <b>纪念日占位已由会话 ③ 实现</b>（V1.5.0 onboarding-recall Story 5.2：与 19:00 WIB 纪念日日扫共用
 * {@link AnniversaryPlan}）。健康提醒线（会话 ①）测试里 mock 本类即可。
 */
@Service
public class RetentionPushCalendar {

    /** D1 / D3 / D7 节点（注册满 N 天，WIB 自然日）。 */
    private static final List<Integer> NODES = List.of(1, 3, 7);

    /** 节点窗口 2 天（同 {@link LifecyclePushPlanner}：漏跑一次能补）。保守判定把窗口两天都算召回日。 */
    private static final int WINDOW_DAYS = 2;

    private final NamedParameterJdbcTemplate jdbc;

    public RetentionPushCalendar(NamedParameterJdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /**
     * 入参里在 {@code wibDate} 这天处在 D1 / D3 / D7 节点窗口的用户（只按注册日期判断）。
     *
     * <p><b>保守判定</b>（PRD 原话）：当天来过的 D1 / D3、分层是「E 已互动」的 D3 实际不会发，但这里照样算召回日，
     * 健康提醒那天照样让开。入参为空 → 空集；一次查询。
     */
    public Set<Long> recallNodeUsers(Collection<Long> userIds, LocalDate wibDate) {
        Set<Long> out = new HashSet<>();
        if (userIds == null || userIds.isEmpty()) {
            return out;
        }
        List<Object[]> rows = jdbc.query(
                "SELECT id, (created_at AT TIME ZONE :tz)::date AS reg FROM users WHERE id IN (:ids)",
                new MapSqlParameterSource().addValue("ids", userIds).addValue("tz", ScheduleWindow.WIB.getId()),
                (rs, i) -> new Object[] {rs.getLong("id"), rs.getObject("reg", LocalDate.class)});
        for (Object[] r : rows) {
            if (inRecallWindow((LocalDate) r[1], wibDate)) {
                out.add((Long) r[0]);
            }
        }
        return out;
    }

    /** 注册日 {@code reg}（WIB）的用户在 {@code day} 是否处在任一节点窗口；注册日为空 → false。 */
    static boolean inRecallWindow(LocalDate reg, LocalDate day) {
        if (reg == null) {
            return false;
        }
        long age = ChronoUnit.DAYS.between(reg, day);
        for (int node : NODES) {
            if (age >= node && age < node + WINDOW_DAYS) {
                return true;
            }
        }
        return false;
    }

    /**
     * 入参里在 {@code wibDate} 这天会收到纪念日推送的宠物（FR-182：正常状态、主人有效、当天满 30 / 100 / 365 天、
     * 且是主人当天满纪念日的宠物里建档最早的那只；同主人同天其他宠物只写通知中心，不算占用）。
     *
     * <p>V1.5.0 onboarding-recall Story 5.2（OR-20，会话 ③ 已实现，签名未动）：一次查询取入参宠物所属主人的
     * <b>全部</b>宠物快照（同主人「最早」要看全部宠物，不只入参），跑与 19:00 WIB 日扫<b>同一个</b>
     * {@link AnniversaryPlan#compute}，返回 {@code push=true} 且在入参里的宠物。入参为空 → 空集。
     */
    public Set<Long> anniversaryPushPets(Collection<Long> petIds, LocalDate wibDate) {
        Set<Long> out = new HashSet<>();
        if (petIds == null || petIds.isEmpty() || wibDate == null) {
            return out;
        }
        List<PetProfileSnapshot> snapshots = jdbc.query("""
                SELECT p.id, p.owner_id, p.name, p.status, p.created_at,
                       (p.created_at AT TIME ZONE :tz)::date AS created_wib,
                       (u.deleted_at IS NULL AND u.status = 'ACTIVE') AS owner_active
                  FROM pet_profiles p
                  LEFT JOIN users u ON u.id = p.owner_id
                 WHERE p.owner_id IN (SELECT owner_id FROM pet_profiles WHERE id IN (:ids))
                 ORDER BY p.created_at, p.id
                """,
                new MapSqlParameterSource().addValue("ids", petIds).addValue("tz", ScheduleWindow.WIB.getId()),
                (rs, i) -> {
                    java.sql.Timestamp created = rs.getTimestamp("created_at");
                    return new PetProfileSnapshot(rs.getLong("id"), rs.getLong("owner_id"), rs.getString("name"),
                            null, null, rs.getObject("created_wib", LocalDate.class), rs.getString("status"),
                            rs.getBoolean("owner_active"), created == null ? null : created.toInstant());
                });
        for (AnniversaryPlan.AnniversaryPush a : AnniversaryPlan.compute(snapshots, wibDate)) {
            if (a.push() && petIds.contains(a.petId())) {
                out.add(a.petId());
            }
        }
        return out;
    }
}
