package com.tailtopia.admin.dashboard;

import static org.assertj.core.api.Assertions.assertThat;

import com.tailtopia.admin.dashboard.domain.DashboardMetric;
import com.tailtopia.admin.dashboard.domain.MetricScope;
import com.tailtopia.admin.dashboard.metrics.MetricQuery;
import com.tailtopia.auth.domain.User;
import com.tailtopia.support.ApiIntegrationTest;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.Statement;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.EnumMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.ConnectionCallback;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * L1（真库）：18 项指标与参考 SQL {@code 内容运营所需数据-20260831.sql} 逐项相等（V1.3.0 Story 3.2 AC4）。
 * <ul>
 * <li>参考 SQL 的 {@code report_days} CTE 替换为固定日期列表；裸 {@code ::date} 随连接时区切日 → 对照跑前 {@code SET TIME ZONE 'Asia/Jakarta'}（同一连接内）。</li>
 * <li>D-38（拍板回写）：有效评论 = VISIBLE 且未删。参考 SQL 只过滤 {@code deleted_at}，对 7 项互动指标按新口径断言：
 * 把参考 SQL 的评论过滤补上 {@code moderation_status = 'VISIBLE'} 后 17 项<b>逐项相等</b>；同时证明未补丁的原版在有非 VISIBLE 评论时确实不等（差值 = 非 VISIBLE 评论贡献）。</li>
 * <li>#5 新增建档用户数（D-28 按人去重）不与参考 SQL 比，按样本增量单独断言。</li>
 * <li>参考 SQL 另打两处 schema 对齐补丁：身份证高清段只算 PawCoin 行 + ID_HD 支付单 PAID（V92 后表语义是「支付尝试」）；#7 档案加时间上界。</li>
 * <li>样本含跨 WIB 日界（23:30 / 次日 00:30，UTC 同一天）、Apple 用户、虚拟作者与虚拟互动者、UNDER_REVIEW 帖、UNDER_REVIEW / 已删评论、已退款问诊单。</li>
 * </ul>
 * 共享测试库不回滚：断言用「插样本前后的增量」而非绝对值。
 */
class MetricQueryParityTest extends ApiIntegrationTest {

    private static final ZoneId WIB = ZoneId.of("Asia/Jakarta");
    /** 远离其它测试数据的三天（WIB）。 */
    private static final LocalDate D0 = LocalDate.of(2021, 3, 9);
    private static final LocalDate D1 = LocalDate.of(2021, 3, 10);
    private static final LocalDate D2 = LocalDate.of(2021, 3, 11);
    private static final List<LocalDate> DAYS = List.of(D0, D1, D2);

    private static final Path REFERENCE_SQL = Path.of("..", "_bmad-output", "planning-artifacts", "v1.3.0", "内容运营所需数据-20260831.sql");

    @Autowired
    private JdbcTemplate jdbc;

    @Autowired
    private List<MetricQuery> queries;

    private static Instant wib(LocalDate d, int hour, int minute) {
        return LocalDateTime.of(d, java.time.LocalTime.of(hour, minute)).atZone(WIB).toInstant();
    }

    private static Timestamp ts(Instant i) {
        return Timestamp.from(i);
    }

    private MetricQuery q(DashboardMetric m) {
        return queries.stream().filter(x -> x.key() == m).findFirst().orElseThrow();
    }

    private Map<DashboardMetric, BigDecimal> snapshot(LocalDate d, MetricScope scope) {
        Map<DashboardMetric, BigDecimal> out = new EnumMap<>(DashboardMetric.class);
        for (DashboardMetric m : DashboardMetric.values()) {
            if (scope == MetricScope.ALL || m.dualScope()) {
                out.put(m, q(m).compute(d, scope));
            }
        }
        return out;
    }

    private static BigDecimal delta(Map<DashboardMetric, BigDecimal> after, Map<DashboardMetric, BigDecimal> before, DashboardMetric m) {
        BigDecimal a = after.get(m) == null ? BigDecimal.ZERO : after.get(m);
        BigDecimal b = before.get(m) == null ? BigDecimal.ZERO : before.get(m);
        return a.subtract(b);
    }

    @Test
    void eighteenMetricsMatchReferenceSqlAndSampleDeltas() throws Exception {
        assertThat(queries).hasSize(18);
        Map<LocalDate, Map<DashboardMetric, BigDecimal>> beforeAll = new LinkedHashMap<>();
        Map<LocalDate, Map<DashboardMetric, BigDecimal>> beforeReal = new LinkedHashMap<>();
        for (LocalDate d : DAYS) {
            beforeAll.put(d, snapshot(d, MetricScope.ALL));
            beforeReal.put(d, snapshot(d, MetricScope.REAL));
        }

        insertSamples();

        // ---- AC4：与参考 SQL（D-38 补丁版）逐项相等（#5 除外）；三天 × 17 项 ----
        Map<LocalDate, BigDecimal[]> patched = runReference(true);
        for (LocalDate d : DAYS) {
            Map<DashboardMetric, BigDecimal> mine = snapshot(d, MetricScope.ALL);
            BigDecimal[] ref = patched.get(d);
            for (DashboardMetric m : DashboardMetric.values()) {
                if (m == DashboardMetric.NEW_PET_OWNERS) {
                    continue;
                }
                BigDecimal r = ref[m.number() - 1];
                if (r == null) {
                    assertThat(mine.get(m)).as("%s@%s null", m.key(), d).isNull();
                } else {
                    assertThat(mine.get(m)).as("%s@%s", m.key(), d).isNotNull();
                    assertThat(mine.get(m).compareTo(r)).as("%s@%s mine=%s ref=%s", m.key(), d, mine.get(m), r).isZero();
                }
            }
        }
        // 未补丁的参考 SQL 把 UNDER_REVIEW 评论也计入互动 → D1 的总互动得分必然比新口径大 5（样本里恰一条非 VISIBLE 评论）
        BigDecimal[] rawRef = runReference(false).get(D1);
        assertThat(rawRef[DashboardMetric.ENGAGEMENT_SCORE.number() - 1]
                .subtract(patched.get(D1)[DashboardMetric.ENGAGEMENT_SCORE.number() - 1])).isEqualByComparingTo("5");

        // ---- 样本增量（证明切日 / 可见性 / 真实用户 / 双口径）----
        Map<DashboardMetric, BigDecimal> a1 = snapshot(D1, MetricScope.ALL);
        Map<DashboardMetric, BigDecimal> a2 = snapshot(D2, MetricScope.ALL);
        Map<DashboardMetric, BigDecimal> r1 = snapshot(D1, MetricScope.REAL);
        Map<DashboardMetric, BigDecimal> r2 = snapshot(D2, MetricScope.REAL);
        Map<DashboardMetric, BigDecimal> b1 = beforeAll.get(D1);
        Map<DashboardMetric, BigDecimal> b2 = beforeAll.get(D2);

        // #1：A 于 D1 23:30 WIB 注册、B（Apple）于 D2 00:30 WIB 注册（UTC 同一天）→ 各计一天；虚拟号不计
        assertThat(delta(a1, b1, DashboardMetric.NEW_USERS)).isEqualByComparingTo("1");
        assertThat(delta(a2, b2, DashboardMetric.NEW_USERS)).isEqualByComparingTo("1");
        assertThat(delta(a2, b2, DashboardMetric.CUMULATIVE_USERS)).isEqualByComparingTo("2");
        // #3/#4 ALL：P1 P2 P4 计入，P3 审核中 / P5 已删除不计；D2 只有 P6
        assertThat(delta(a1, b1, DashboardMetric.NEW_POSTS)).isEqualByComparingTo("3");
        assertThat(delta(a1, b1, DashboardMetric.POSTING_USERS)).isEqualByComparingTo("2");
        assertThat(delta(a2, b2, DashboardMetric.NEW_POSTS)).isEqualByComparingTo("1");
        // REAL：虚拟作者 P4 不计
        assertThat(delta(r1, beforeReal.get(D1), DashboardMetric.NEW_POSTS)).isEqualByComparingTo("2");
        assertThat(delta(r1, beforeReal.get(D1), DashboardMetric.POSTING_USERS)).isEqualByComparingTo("1");
        // #5（D-28 按人去重）：A 首次建档 D1；#6 累计 +1；#7 A 于 D1 发 GROWTH_MOMENT 且有档案
        assertThat(delta(a1, b1, DashboardMetric.NEW_PET_OWNERS)).isEqualByComparingTo("1");
        assertThat(delta(a2, b2, DashboardMetric.NEW_PET_OWNERS)).isEqualByComparingTo("0");
        assertThat(delta(a2, b2, DashboardMetric.CUMULATIVE_PET_OWNERS)).isEqualByComparingTo("1");
        assertThat(delta(a1, b1, DashboardMetric.DIARY_PET_OWNERS)).isEqualByComparingTo("1");
        // #8 ALL：P1（B 赞 / V 赞 / B 评 / V 评）+ P4（A 赞）；REAL：只 P1（V 的互动与虚拟作者 P4 不计）
        assertThat(delta(a1, b1, DashboardMetric.INTERACTED_POSTS)).isEqualByComparingTo("2");
        assertThat(delta(r1, beforeReal.get(D1), DashboardMetric.INTERACTED_POSTS)).isEqualByComparingTo("1");
        // #9：P2 当日无互动（B 的赞落在 D2 00:30 WIB）→ 沉默；两口径都 +1
        assertThat(delta(a1, b1, DashboardMetric.SILENT_POSTS)).isEqualByComparingTo("1");
        assertThat(delta(r1, beforeReal.get(D1), DashboardMetric.SILENT_POSTS)).isEqualByComparingTo("1");
        // #10 ALL D1：赞 3（B→P1、V→P1、A→P4）+ 有效评论 2（B、V 于 P1；UNDER_REVIEW / 已删不计）×5 = 13；REAL：1 + 5 = 6
        assertThat(delta(a1, b1, DashboardMetric.ENGAGEMENT_SCORE)).isEqualByComparingTo("13");
        assertThat(delta(r1, beforeReal.get(D1), DashboardMetric.ENGAGEMENT_SCORE)).isEqualByComparingTo("6");
        // D2：只有 B→P2 一个赞（00:30 WIB，UTC 仍是 D1）→ 切日按 WIB
        assertThat(delta(a2, b2, DashboardMetric.ENGAGEMENT_SCORE)).isEqualByComparingTo("1");
        // #11：D1 新帖截至 D1 24:00 累计得分 = P1 12（2 赞 + 2 有效评论）+ P2 0 + P4 1 = 13（ALL）；REAL = P1 6
        assertThat(delta(a1, b1, DashboardMetric.NEW_POSTS_SCORE)).isEqualByComparingTo("13");
        assertThat(delta(r1, beforeReal.get(D1), DashboardMetric.NEW_POSTS_SCORE)).isEqualByComparingTo("6");
        // #14：D2 累计比 D1 多 1（B→P2）
        assertThat(delta(a2, b2, DashboardMetric.ENGAGEMENT_SCORE_ALL_TIME)
                .subtract(delta(a1, b1, DashboardMetric.ENGAGEMENT_SCORE_ALL_TIME))).isEqualByComparingTo("1");
        // #15/#16：A 两笔 PAID（问诊 + 充值）；V 的 PAID 与 A 的 PENDING 不计
        assertThat(delta(a1, b1, DashboardMetric.PAYING_USERS_CASH)).isEqualByComparingTo("1");
        assertThat(delta(a1, b1, DashboardMetric.PAYMENTS_CASH)).isEqualByComparingTo("2");
        // #17/#18 D1：A（已退款问诊 + 充值）+ B（AI 解锁 COMPLETED）= 2 人 3 笔；D2：A 身份证高清 1 人 1 笔
        assertThat(delta(a1, b1, DashboardMetric.PAYING_USERS_INCL_PAWCOIN)).isEqualByComparingTo("2");
        assertThat(delta(a1, b1, DashboardMetric.PAYMENTS_INCL_PAWCOIN)).isEqualByComparingTo("3");
        assertThat(delta(a2, b2, DashboardMetric.PAYING_USERS_INCL_PAWCOIN)).isEqualByComparingTo("1");
        assertThat(delta(a2, b2, DashboardMetric.PAYMENTS_INCL_PAWCOIN)).isEqualByComparingTo("1");

        // ---- AC4 双口径：有合成账号的 D1 上 REAL ≤ ALL；无合成账号的 D2 上 REAL 增量 = ALL 增量 ----
        for (DashboardMetric m : DashboardMetric.values()) {
            if (!m.dualScope()) {
                continue;
            }
            // #9 沉默帖子：REAL 口径把虚拟互动过滤掉后，只收到虚拟赞的真实帖在 REAL 里反而「沉默」→ REAL 可大于 ALL，故豁免
            if (a1.get(m) != null && r1.get(m) != null && m != DashboardMetric.ALL_POSTS_AVG_SCORE
                    && m != DashboardMetric.INTERACTED_POSTS_AVG_SCORE && m != DashboardMetric.SILENT_POSTS) {
                assertThat(r1.get(m).compareTo(a1.get(m))).as("REAL<=ALL %s@D1", m.key()).isLessThanOrEqualTo(0);
            }
            if (m != DashboardMetric.ALL_POSTS_AVG_SCORE && m != DashboardMetric.INTERACTED_POSTS_AVG_SCORE
                    && m != DashboardMetric.ENGAGEMENT_SCORE_ALL_TIME) {
                assertThat(delta(r2, beforeReal.get(D2), m)).as("REAL==ALL delta %s@D2", m.key())
                        .isEqualByComparingTo(delta(a2, b2, m));
            }
        }
    }

    // ---------------------------------------------------------------- 样本

    private long a;
    private long b;
    private long v;
    private long p1;
    private long p2;
    private long p4;

    private void insertSamples() {
        long n = SEQ.incrementAndGet();
        a = users.save(User.newGoogleUser("parity32-g-" + n, "p32g" + n + "@t.test", "A", null)).getId();
        b = users.save(User.newAppleUser("parity32-a-" + n, "p32a" + n + "@t.test")).getId();
        v = users.save(User.newVirtual("virtual:parity32-" + n, "马甲" + n, null, 1L)).getId();
        jdbc.update("UPDATE users SET created_at = ? WHERE id = ?", ts(wib(D1, 23, 30)), a);
        jdbc.update("UPDATE users SET created_at = ? WHERE id = ?", ts(wib(D2, 0, 30)), b);
        jdbc.update("UPDATE users SET created_at = ? WHERE id = ?", ts(wib(D1, 9, 0)), v);

        // 档案：A 于 D1 首次建档
        jdbc.update("INSERT INTO pet_profiles (owner_id, name, card_token, created_at, updated_at, pet_type, is_system_default_name)"
                + " VALUES (?, ?, ?, ?, ?, 'CAT', false)", a, "P" + n, "ct32" + UUID.randomUUID().toString().replace("-", "").substring(0, 20),
                ts(wib(D1, 10, 0)), ts(wib(D1, 10, 0)));

        p1 = post(a, "DAILY", "PUBLISHED", wib(D1, 10, 0), null);
        p2 = post(a, "GROWTH_MOMENT", "PUBLISHED", wib(D1, 11, 0), null);
        post(a, "DAILY", "UNDER_REVIEW", wib(D1, 12, 0), null);
        p4 = post(v, "DAILY", "PUBLISHED", wib(D1, 13, 0), null);
        post(a, "DAILY", "PUBLISHED", wib(D1, 23, 30), wib(D1, 23, 40));
        post(b, "DAILY", "PUBLISHED", wib(D2, 0, 30), null);

        like(p1, b, wib(D1, 14, 0));
        like(p1, v, wib(D1, 14, 5));
        like(p4, a, wib(D1, 14, 10));
        like(p2, b, wib(D2, 0, 30));

        comment(p1, b, "VISIBLE", wib(D1, 15, 0), null);
        comment(p1, b, "UNDER_REVIEW", wib(D1, 15, 5), null);
        comment(p2, b, "VISIBLE", wib(D1, 15, 10), wib(D1, 15, 20));
        comment(p1, v, "VISIBLE", wib(D1, 15, 30), null);

        // 现金到账：A 问诊 PAID、A 充值 PAID、V 充值 PAID（合成不计）、A 待支付（不计）
        intent(a, "VET_CONSULT", "PAID", wib(D1, 16, 0));
        intent(a, "PAWCOIN_TOPUP", "PAID", wib(D1, 16, 10));
        intent(v, "PAWCOIN_TOPUP", "PAID", wib(D1, 16, 20));
        intent(a, "AI_UNLOCK", "PENDING", wib(D1, 16, 30));
        // 含 PawCoin 消费：A 已退款问诊单（当日仍计）、B AI 解锁 COMPLETED、B AI 待支付（不计）、A 身份证高清 D2
        jdbc.update("INSERT INTO consult_orders (order_token, user_id, vet_id, pet_profile_id, status, amount, refund_rejected,"
                + " paid_at, created_at, updated_at, rebroadcast_count) VALUES (?, ?, 1, 1, 'REFUNDED', 15000, false, ?, ?, ?, 0)",
                tok(), a, ts(wib(D1, 17, 0)), ts(wib(D1, 17, 0)), ts(wib(D1, 17, 0)));
        jdbc.update("INSERT INTO ai_consult_orders (order_token, user_id, triage_task_id, amount, pay_channel, status, paid_at,"
                + " created_at, updated_at) VALUES (?, ?, 1, 5000, 'PAWCOIN', 'COMPLETED', ?, ?, ?)",
                tok(), b, ts(wib(D1, 17, 10)), ts(wib(D1, 17, 10)), ts(wib(D1, 17, 10)));
        jdbc.update("INSERT INTO ai_consult_orders (order_token, user_id, triage_task_id, amount, pay_channel, status, paid_at,"
                + " created_at, updated_at) VALUES (?, ?, 1, 5000, 'QRIS', 'PENDING_PAYMENT', NULL, ?, ?)",
                tok(), b, ts(wib(D1, 17, 20)), ts(wib(D1, 17, 20)));
        jdbc.update("INSERT INTO id_card_hd_purchases (user_id, pay_channel, purchased_at) VALUES (?, 'PAWCOIN', ?)",
                a, ts(wib(D2, 0, 30)));
        // B 扫码买高清图但没付：V92 起 QRIS 下单即插「支付尝试」行 + PENDING intent → 不是成交，#17/#18 不计
        long hdAttempt = intent(b, "ID_HD", "PENDING", wib(D1, 18, 0));
        jdbc.update("INSERT INTO id_card_hd_purchases (user_id, pay_channel, payment_intent_id, purchased_at) VALUES (?, 'QRIS', ?, ?)",
                b, hdAttempt, ts(wib(D1, 18, 0)));
    }

    private static String tok() {
        return UUID.randomUUID().toString().replace("-", "").substring(0, 32);
    }

    private long post(long author, String type, String status, Instant createdAt, Instant deletedAt) {
        return jdbc.queryForObject("INSERT INTO content_posts (author_id, type, status, created_at, updated_at, deleted_at, content_version)"
                + " VALUES (?, ?, ?, ?, ?, ?, 1) RETURNING id", Long.class, author, type, status, ts(createdAt), ts(createdAt),
                deletedAt == null ? null : ts(deletedAt));
    }

    private void like(long post, long user, Instant at) {
        jdbc.update("INSERT INTO content_likes (post_id, user_id, created_at) VALUES (?, ?, ?)", post, user, ts(at));
    }

    private void comment(long post, long author, String moderation, Instant at, Instant deletedAt) {
        jdbc.update("INSERT INTO comments (post_id, author_id, body, created_at, updated_at, moderation_status, deleted_at, content_version)"
                + " VALUES (?, ?, 'parity', ?, ?, ?, ?, 1)", post, author, ts(at), ts(at), moderation, deletedAt == null ? null : ts(deletedAt));
    }

    private long intent(long user, String purpose, String status, Instant at) {
        return jdbc.queryForObject("INSERT INTO payment_intents (public_token, user_id, purpose, channel, amount, currency, status, version, created_at, updated_at)"
                + " VALUES (?, ?, ?, 'QRIS', 10000, 'IDR', ?, 0, ?, ?) RETURNING id", Long.class, tok(), user, purpose, status, ts(at), ts(at));
    }

    // ---------------------------------------------------------------- 参考 SQL

    private static final Pattern REPORT_DAYS = Pattern.compile("report_days AS \\(.*?\\) AS gs\\s*\\)", Pattern.DOTALL);

    /** 读参考 SQL，替换 report_days 为固定三天；{@code d38} = 是否把评论过滤补成 VISIBLE 且未删。返回 日期 → 18 列（按 # 顺序，index = #-1）。 */
    private Map<LocalDate, BigDecimal[]> runReference(boolean d38) throws Exception {
        String sql = Files.readString(REFERENCE_SQL, StandardCharsets.UTF_8);
        String days = DAYS.stream().map(d -> "DATE '" + d + "'").reduce((x, y) -> x + ", " + y).orElseThrow();
        Matcher m = REPORT_DAYS.matcher(sql);
        assertThat(m.find()).as("参考 SQL 的 report_days CTE 形状变了，请同步本测试").isTrue();
        sql = m.replaceFirst(Matcher.quoteReplacement("report_days AS (SELECT unnest(ARRAY[" + days + "]) AS report_date)"));
        // 与现网 schema 对齐的两处补丁（3-2 Notes「有意差异」）：
        // ① id_card_hd_purchases 自 V92 起是「支付尝试」表：PawCoin 行才是成交，QRIS 成交按 ID_HD 支付单 PAID 到账日
        String hdOld = "    SELECT hd.user_id, hd.purchased_at::date\n    FROM id_card_hd_purchases hd\n    JOIN real_users ru ON ru.id = hd.user_id\n";
        assertThat(sql).as("参考 SQL 的身份证高清段形状变了，请同步本测试").contains(hdOld);
        sql = sql.replace(hdOld, hdOld + "    WHERE hd.pay_channel = 'PAWCOIN'\n    UNION ALL\n"
                + "    SELECT pi2.user_id, pi2.updated_at::date FROM payment_intents pi2 JOIN real_users ru ON ru.id = pi2.user_id\n"
                + "    WHERE pi2.status = 'PAID' AND pi2.purpose = 'ID_HD'\n");
        // ② #7 档案存在性加「截至当日 24:00」上界（补跑可复现）
        String diaryOld = "WHERE pp.owner_id = cp.author_id)";
        assertThat(sql).contains(diaryOld);
        sql = sql.replace(diaryOld, "WHERE pp.owner_id = cp.author_id AND pp.created_at::date <= rd.report_date)");
        if (d38) {
            sql = sql.replace("FROM comments WHERE deleted_at IS NULL", "FROM comments WHERE deleted_at IS NULL AND moderation_status = 'VISIBLE'")
                    .replace("cm.deleted_at IS NULL", "cm.deleted_at IS NULL AND cm.moderation_status = 'VISIBLE'");
            assertThat(sql).contains("moderation_status = 'VISIBLE'");
        }
        final String finalSql = sql;
        return jdbc.execute((ConnectionCallback<Map<LocalDate, BigDecimal[]>>) con -> {
            Map<LocalDate, BigDecimal[]> out = new LinkedHashMap<>();
            try (Statement st = con.createStatement()) {
                st.execute("SET TIME ZONE 'Asia/Jakarta'");
            }
            try (PreparedStatement ps = con.prepareStatement(finalSql); ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    BigDecimal[] row = new BigDecimal[18];
                    for (int i = 0; i < 18; i++) {
                        row[i] = rs.getBigDecimal(i + 2);
                    }
                    out.put(rs.getDate(1).toLocalDate(), row);
                }
            } finally {
                try (Statement st = con.createStatement()) {
                    st.execute("RESET TIME ZONE");
                }
            }
            assertThat(out.keySet()).containsExactlyElementsOf(DAYS);
            return out;
        });
    }
}
