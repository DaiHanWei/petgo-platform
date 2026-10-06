package com.tailtopia.admin.usermgmt.service;

import com.tailtopia.admin.usermgmt.dto.UserPurchasesView;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.Map;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 用户详情抽屉「已购解锁」页签的取数（V1.3.2 后台 AB-23）。只读、无 PII，权限沿用用户详情（不新增）。
 *
 * <p>🔴 <b>读业务表的解锁状态，不读支付表</b>：PawCoin 付费不建 {@code payment_intents}，读支付表会让
 * 「用 PawCoin 买过」的用户在后台查不到任何痕迹 —— 那正是本页签要补的缺口。KTP 的权威口径同理是卡上的
 * {@code id_cards.hd_unlocked}，不是购买记录表的行数（{@code id_card_hd_purchases} 是「支付尝试 / 收据」，QRIS 下单即插行）。
 *
 * <p>四条查询各一次、按用户一把取完（与抽屉其余五页签同一次聚合，切页签不再请求）。单账号单宠物（FR-11），
 * 宠物经 {@code pet_profiles.owner_id} 归属到用户。
 */
@Service
public class AdminUserPurchasesQuery {

    /**
     * KTP：已解锁的卡 + 真实付款时刻（2026-10-02 定）。
     * <ul>
     *   <li>QRIS：购买行指向的支付单 PAID 时的 {@code updated_at}（到账时刻，与看板 #15 同口径）——
     *       不用 {@code purchased_at}，那是下单时刻。</li>
     *   <li>PawCoin：{@code purchased_at}（当场扣币，下单即付款）。</li>
     * </ul>
     * 一张卡可能有多条购买行（如先建 QRIS 单未付、再改用 PawCoin），取最早一次真付款。都没有 → null。
     */
    static final String KTP_SQL = """
            SELECT c.serial_id, c.name,
                   (SELECT MIN(CASE WHEN h.pay_channel = 'PAWCOIN' THEN h.purchased_at
                                    WHEN pi.status = 'PAID' THEN pi.updated_at END)
                      FROM id_card_hd_purchases h
                      LEFT JOIN payment_intents pi ON pi.id = h.payment_intent_id
                     WHERE h.card_id = c.id) AS unlocked_at
              FROM id_cards c
             WHERE c.user_id = :u AND c.hd_unlocked
             ORDER BY unlocked_at DESC NULLS LAST, c.id DESC
            """;

    /** 护照快照：只取已付款的，版本号按该宠物付款先后编号（未付款的不占号，2026-10-02 定）。 */
    static final String SNAPSHOT_SQL = """
            SELECT p.name, s.stamp_count, s.paid_at,
                   ROW_NUMBER() OVER (PARTITION BY s.pet_profile_id ORDER BY s.paid_at, s.id) AS version
              FROM passport_snapshots s
              JOIN pet_profiles p ON p.id = s.pet_profile_id
             WHERE p.owner_id = :u AND s.paid_at IS NOT NULL
             ORDER BY s.paid_at DESC, s.id DESC
            """;

    /** 登机牌：已解锁的，含合并后作废（superseded）的那张 —— 列出并标「已合并」（2026-10-02 定）。 */
    static final String BOARDING_SQL = """
            SELECT p.name AS pet_name, pl.name AS place_name, b.unlocked_at, b.superseded_at IS NOT NULL AS superseded
              FROM boarding_pass_unlocks b
              JOIN pet_profiles p ON p.id = b.pet_profile_id
              JOIN places pl ON pl.id = b.place_id
             WHERE p.owner_id = :u AND b.unlocked_at IS NOT NULL
             ORDER BY b.unlocked_at DESC, b.id DESC
            """;

    /** Tailsonality：已解锁的结果，角色代号 = 类型码 + 能量档。 */
    static final String TAILSONALITY_SQL = """
            SELECT p.name, r.type_code, r.energy, r.unlocked_at
              FROM tailsonality_results r
              JOIN pet_profiles p ON p.id = r.pet_profile_id
             WHERE r.user_id = :u AND r.unlocked_at IS NOT NULL
             ORDER BY r.unlocked_at DESC, r.id DESC
            """;

    private final NamedParameterJdbcTemplate jdbc;

    public AdminUserPurchasesQuery(NamedParameterJdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @Transactional(readOnly = true)
    public UserPurchasesView forUser(long userId) {
        Map<String, Object> p = Map.of("u", userId);
        return new UserPurchasesView(
                jdbc.query(KTP_SQL, p, (rs, i) -> new UserPurchasesView.KtpCard(
                        rs.getLong("serial_id"), rs.getString("name"), instant(rs, "unlocked_at"))),
                jdbc.query(SNAPSHOT_SQL, p, (rs, i) -> new UserPurchasesView.PassportSnapshot(
                        rs.getString("name"), rs.getInt("version"), rs.getInt("stamp_count"), instant(rs, "paid_at"))),
                jdbc.query(BOARDING_SQL, p, (rs, i) -> new UserPurchasesView.BoardingPass(
                        rs.getString("pet_name"), rs.getString("place_name"), instant(rs, "unlocked_at"),
                        rs.getBoolean("superseded"))),
                jdbc.query(TAILSONALITY_SQL, p, (rs, i) -> new UserPurchasesView.TailsonalityResult(
                        rs.getString("name"), rs.getString("type_code").trim() + "-" + rs.getString("energy").trim(),
                        instant(rs, "unlocked_at"))));
    }

    private static Instant instant(ResultSet rs, String col) throws SQLException {
        Timestamp t = rs.getTimestamp(col);
        return t == null ? null : t.toInstant();
    }
}
