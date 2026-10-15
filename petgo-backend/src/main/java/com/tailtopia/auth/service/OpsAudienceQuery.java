package com.tailtopia.auth.service;

import com.tailtopia.notify.ops.domain.AudienceSpec;
import com.tailtopia.notify.ops.domain.OpsRecipient;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Objects;
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
 * 🔒 不 log 任何收件人 id 列表、手机号、语言（调用方只记活动 id 与人数）。
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
        return jdbc.query(q.sql(), bind(q.params()),
                (rs, rowNum) -> new OpsRecipient(rs.getLong("id"), rs.getString("locale")));
    }

    /**
     * 人数预估（V1.3.4 AB-31）：按<b>当前</b>人群条件实时计数，口径与 {@link #resolve} 一致。
     * 预估与真正发送是两次查询，期间人数可能变化，后台确认框写「约 N 人」。
     *
     * @param ids 仅 IDS 模式：运营粘贴的用户 id（可含重复 / 无效 id，去重后分批 {@code IN} 计有效人数）；其余模式忽略
     */
    @Transactional(readOnly = true)
    public long count(AudienceSpec spec, Collection<Long> ids) {
        if (spec.mode() != AudienceSpec.Mode.IDS) {
            OpsAudienceSql.Query q = OpsAudienceSql.count(spec, WIB);
            Long n = jdbc.queryForObject(q.sql(), bind(q.params()), Long.class);
            return n == null ? 0 : n;
        }
        if (ids == null || ids.isEmpty()) {
            return 0;
        }
        List<Long> distinct = ids.stream().filter(Objects::nonNull).distinct().toList();
        String sql = OpsAudienceSql.countIds();
        long total = 0;
        // 分批：PostgreSQL 单条语句绑定参数上限 65535，上限 5 万个 id 一把塞不进去
        for (int from = 0; from < distinct.size(); from += ID_CHUNK) {
            List<Long> chunk = distinct.subList(from, Math.min(from + ID_CHUNK, distinct.size()));
            Long n = jdbc.queryForObject(sql, new MapSqlParameterSource("ids", chunk), Long.class);
            total += n == null ? 0 : n;
        }
        return total;
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
