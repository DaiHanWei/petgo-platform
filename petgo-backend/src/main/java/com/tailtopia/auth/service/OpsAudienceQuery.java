package com.tailtopia.auth.service;

import com.tailtopia.notify.ops.domain.AudienceSpec;
import com.tailtopia.notify.ops.domain.OpsRecipient;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 运营通知人群解析（V1.3.4 Story 6.2 · AC1）。users 归 auth 模块，notify 模块不直访 users 表——
 * 沿用 {@link AccountQueryService} 作读口的约定，人群查询放这里；对 {@code pet_profiles} /
 * {@code shop_orders} 只做只读 {@code EXISTS}。
 *
 * <p>SQL 由纯函数 {@link OpsAudienceSql#build} 生成，本类只执行。结果按 {@code users.id} 升序。
 * 🔒 不 log 任何收件人 id 列表、手机号、语言、昵称、宠物名（调用方只记活动 id 与人数）。
 */
@Service
public class OpsAudienceQuery {

    /** WIB 自然日（D-16）。 */
    static final ZoneId WIB = ZoneId.of("Asia/Jakarta");

    /** IDS 预估每批 id 个数（远低于 PostgreSQL 65535 个绑定参数的上限）。 */
    static final int ID_CHUNK = 10_000;

    private final NamedParameterJdbcTemplate jdbc;

    public OpsAudienceQuery(NamedParameterJdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /** 解析活动人群（按实际发送时刻调用；IDS 模式取子表 {@code campaignId} 的指定用户）。 */
    @Transactional(readOnly = true)
    public List<OpsRecipient> resolve(AudienceSpec spec, long campaignId) {
        OpsAudienceSql.Query q = OpsAudienceSql.build(spec, campaignId, WIB);
        return jdbc.query(q.sql(), bind(q.params()), RECIPIENT);
    }

    /** 收件人四列（V1.4.2 Story 7.1：昵称 / 宠物名随人群一条 SQL 带出，不逐人查）。 */
    private static final RowMapper<OpsRecipient> RECIPIENT = (rs, rowNum) -> new OpsRecipient(rs.getLong("id"),
            rs.getString("locale"), rs.getString("nickname"), rs.getString("pet_name"));

    /**
     * 人数预估结果（V1.4.2 Story 7.1 · D-16）：{@code total} = 基础过滤 + 人群条件；{@code withPet} = 其中有宠物的人
     * （文案用了宠物变量时的净人数）。
     */
    public record Reach(long total, long withPet) {

        public static final Reach EMPTY = new Reach(0, 0);

        Reach plus(Reach o) {
            return new Reach(total + o.total, withPet + o.withPet);
        }
    }

    private static final RowMapper<Reach> REACH = (rs, rowNum) -> new Reach(rs.getLong("total"),
            rs.getLong("with_pet"));

    /**
     * 人数预估（V1.3.4 AB-31）：按<b>当前</b>人群条件实时计数，口径与 {@link #resolve} 一致。
     * 预估与真正发送是两次查询，期间人数可能变化，后台确认框写「约 N 人」。
     *
     * @param ids 仅 IDS 模式：运营粘贴的用户 id（可含重复 / 无效 id，去重后分批 {@code IN} 计有效人数）；其余模式忽略
     */
    @Transactional(readOnly = true)
    public Reach count(AudienceSpec spec, Collection<Long> ids) {
        if (spec.mode() != AudienceSpec.Mode.IDS) {
            OpsAudienceSql.Query q = OpsAudienceSql.count(spec, WIB);
            Reach r = jdbc.queryForObject(q.sql(), bind(q.params()), REACH);
            return r == null ? Reach.EMPTY : r;
        }
        if (ids == null || ids.isEmpty()) {
            return Reach.EMPTY;
        }
        List<Long> distinct = ids.stream().filter(Objects::nonNull).distinct().toList();
        String sql = OpsAudienceSql.countIds();
        Reach total = Reach.EMPTY;
        // 分批：PostgreSQL 单条语句绑定参数上限 65535，上限 5 万个 id 一把塞不进去
        for (int from = 0; from < distinct.size(); from += ID_CHUNK) {
            List<Long> chunk = distinct.subList(from, Math.min(from + ID_CHUNK, distinct.size()));
            Reach r = jdbc.queryForObject(sql, new MapSqlParameterSource("ids", chunk), REACH);
            total = r == null ? total : total.plus(r);
        }
        return total;
    }

    /**
     * 后台「预览用户」（V1.4.2 Story 7.1 · AC8）：从<b>本次人群</b>随机取 1 人（含昵称 / 宠物名）。
     * 尽量避开 {@code excludeUserId}（「换一个」）；只剩他一人时仍返回他；人群 0 人 → empty。只读。
     *
     * <p>⚠️ 随机排序 + 取一行在这里拼，而不是写进 {@link OpsAudienceSql}：后者在 {@code PetOwnerLookupGuardTest}
     * 白名单内，白名单文件禁止取单行写法；这里抽的是<b>用户</b>不是宠物，不违背守卫本意。
     *
     * @param ids 仅 IDS 模式：表单里的用户 id（活动未建）；打乱后按 {@value #ID_CHUNK} 一批依次查，命中即返回
     * @param requirePet 文案用了宠物变量：只抽有宠物的人
     */
    @Transactional(readOnly = true)
    public Optional<OpsRecipient> sample(AudienceSpec spec, Collection<Long> ids, boolean requirePet,
            Long excludeUserId) {
        Optional<OpsRecipient> hit = sampleOnce(spec, ids, requirePet, excludeUserId);
        if (hit.isEmpty() && excludeUserId != null) {
            hit = sampleOnce(spec, ids, requirePet, null);
        }
        return hit;
    }

    private Optional<OpsRecipient> sampleOnce(AudienceSpec spec, Collection<Long> ids, boolean requirePet,
            Long excludeUserId) {
        OpsAudienceSql.Query q = OpsAudienceSql.sampleBase(spec, WIB, Instant.now(), requirePet, excludeUserId);
        String sql = q.sql() + RANDOM_ONE;
        if (spec.mode() != AudienceSpec.Mode.IDS) {
            return jdbc.query(sql, bind(q.params()), RECIPIENT).stream().findFirst();
        }
        if (ids == null || ids.isEmpty()) {
            return Optional.empty();
        }
        List<Long> shuffled = new ArrayList<>(ids.stream().filter(Objects::nonNull).distinct().toList());
        Collections.shuffle(shuffled);
        for (int from = 0; from < shuffled.size(); from += ID_CHUNK) {
            MapSqlParameterSource params = bind(q.params());
            params.addValue("ids", shuffled.subList(from, Math.min(from + ID_CHUNK, shuffled.size())));
            Optional<OpsRecipient> hit = jdbc.query(sql, params, RECIPIENT).stream().findFirst();
            if (hit.isPresent()) {
                return hit;
            }
        }
        return Optional.empty();
    }

    /** 随机取一人（当前规模几万用户，全表随机排序可接受）。 */
    private static final String RANDOM_ONE = " ORDER BY random() LIMIT 1";

    /** 指定用户 ID 的归类（{@link OpsAudienceSql#classifyIds()}）。 */
    public enum IdStatus {
        OK,
        NOT_FOUND,
        DELETED,
        BANNED,
        VIRTUAL
    }

    /**
     * 逐个归类指定用户 ID（V1.3.4 AB-31 返工 · CSV 校验）。库里查不到的 id 记 {@link IdStatus#NOT_FOUND}。
     * {@code OK} 的个数与 {@link #count} 在 IDS 模式下的 {@code total} 同口径。
     */
    @Transactional(readOnly = true)
    public Map<Long, IdStatus> classify(Collection<Long> ids) {
        Map<Long, IdStatus> out = new java.util.HashMap<>();
        if (ids == null || ids.isEmpty()) {
            return out;
        }
        List<Long> distinct = ids.stream().filter(Objects::nonNull).distinct().toList();
        String sql = OpsAudienceSql.classifyIds();
        for (int from = 0; from < distinct.size(); from += ID_CHUNK) {
            List<Long> chunk = distinct.subList(from, Math.min(from + ID_CHUNK, distinct.size()));
            jdbc.query(sql, new MapSqlParameterSource("ids", chunk),
                    rs -> {
                        out.put(rs.getLong("id"), IdStatus.valueOf(rs.getString("k")));
                    });
            for (Long id : chunk) {
                out.putIfAbsent(id, IdStatus.NOT_FOUND);
            }
        }
        return out;
    }

    /**
     * 开屏弹窗（V1.4.1）：该用户此刻是否命中四维人群条件。条件为空 → true（不筛）。
     * 口径与运营通知同一份（{@link OpsAudienceSql#appendTargeting}）。
     */
    @Transactional(readOnly = true)
    public boolean matches(long userId, com.tailtopia.ops.audience.AudienceFilters filters, Instant now) {
        if (filters.isEmpty()) {
            return true;
        }
        OpsAudienceSql.Query q = OpsAudienceSql.matchesUser(userId, filters, now);
        return Boolean.TRUE.equals(jdbc.queryForObject(q.sql(), bind(q.params()), Boolean.class));
    }

    /** timestamptz 精确绑定：Instant → UTC OffsetDateTime（不依赖 JVM 默认时区）。 */
    private static MapSqlParameterSource bind(Map<String, Object> raw) {
        MapSqlParameterSource params = new MapSqlParameterSource();
        for (Map.Entry<String, Object> e : raw.entrySet()) {
            Object v = e.getValue();
            params.addValue(e.getKey(), v instanceof Instant i ? OffsetDateTime.ofInstant(i, ZoneOffset.UTC) : v);
        }
        return params;
    }
}
