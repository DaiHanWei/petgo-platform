package com.tailtopia.auth.service;

import com.tailtopia.notify.ops.domain.AudienceSpec;
import com.tailtopia.notify.ops.domain.OpsRecipient;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
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

    private final NamedParameterJdbcTemplate jdbc;

    public OpsAudienceQuery(NamedParameterJdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /** 解析活动人群（按实际发送时刻调用；IDS 模式取子表 {@code campaignId} 的指定用户）。 */
    @Transactional(readOnly = true)
    public List<OpsRecipient> resolve(AudienceSpec spec, long campaignId) {
        OpsAudienceSql.Query q = OpsAudienceSql.build(spec, campaignId, WIB);
        MapSqlParameterSource params = new MapSqlParameterSource();
        for (Map.Entry<String, Object> e : q.params().entrySet()) {
            Object v = e.getValue();
            // timestamptz 精确绑定：Instant → UTC OffsetDateTime（不依赖 JVM 默认时区）。
            params.addValue(e.getKey(), v instanceof Instant i ? OffsetDateTime.ofInstant(i, ZoneOffset.UTC) : v);
        }
        return jdbc.query(q.sql(), params,
                (rs, rowNum) -> new OpsRecipient(rs.getLong("id"), rs.getString("locale")));
    }
}
