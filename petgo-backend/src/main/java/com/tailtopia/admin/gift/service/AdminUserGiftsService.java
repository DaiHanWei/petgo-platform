package com.tailtopia.admin.gift.service;

import com.tailtopia.admin.gift.dto.GiftOrderAdminRow;
import com.tailtopia.admin.gift.dto.UserGiftsTab;
import java.util.List;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 用户抽屉「收送礼记录」页签取数（V1.4.0 ③ gift Story 3.2 · AC5，UI M4 ~ M6）。只读，权限沿用用户抽屉 {@code user.view}。
 *
 * <ul>
 *   <li>送出 = 该用户作为送礼人的全部订单（全部状态）；收到 = 该用户作为 {@code owner_id} 的 {@code DELIVERED} 订单
 *       （含已删宠物的历史行；② 多宠物合入后按 {@code owner_id} 天然覆盖全部宠物）。</li>
 *   <li>一次请求 = 两条 SQL：计数 / 摘要一条聚合 + 当前子列表一页（名字与 token 同列表一条 JOIN 取齐，宠物名取快照，禁 N+1）。</li>
 *   <li>注销用户（抽屉只读）照常显示历史：订单保留（AD-12）。</li>
 * </ul>
 */
@Service
public class AdminUserGiftsService {

    /** 两张子表各自每页条数（AC5.5）。 */
    public static final int PAGE_SIZE = 10;

    static final String STATS = """
            SELECT count(*) FILTER (WHERE o.sender_id = :u) AS sent_count,
                   COALESCE(SUM(o.total) FILTER (WHERE o.sender_id = :u AND o.status = 'DELIVERED'), 0) AS sent_total,
                   count(*) FILTER (WHERE o.owner_id = :u AND o.status = 'DELIVERED') AS recv_count,
                   COALESCE(SUM(o.quantity) FILTER (WHERE o.owner_id = :u AND o.status = 'DELIVERED'), 0) AS recv_qty,
                   count(DISTINCT o.sender_id) FILTER (WHERE o.owner_id = :u AND o.status = 'DELIVERED') AS recv_senders,
                   count(DISTINCT COALESCE(CAST(o.pet_id AS VARCHAR), 'deleted:' || o.pet_name))
                       FILTER (WHERE o.owner_id = :u AND o.status = 'DELIVERED') AS recv_pets,
                   MIN(o.pet_name) FILTER (WHERE o.owner_id = :u AND o.status = 'DELIVERED') AS recv_pet_name
              FROM gift_orders o
             WHERE o.sender_id = :u OR o.owner_id = :u
            """;

    static final String SENT_WHERE = " WHERE o.sender_id = :u";
    static final String RECEIVED_WHERE = " WHERE o.owner_id = :u AND o.status = 'DELIVERED'";

    private record Stats(long sentCount, long sentTotal, long recvCount, long recvQty, long recvSenders,
            long recvPets, String recvPetName) {
    }

    private final NamedParameterJdbcTemplate jdbc;

    public AdminUserGiftsService(NamedParameterJdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /** 首次打开抽屉：默认「送出」第 1 页 + 两个计数（AC5.7）。 */
    @Transactional(readOnly = true)
    public UserGiftsTab firstPage(long userId) {
        return page(userId, UserGiftsTab.SENT, 0);
    }

    /** 胶囊切换 / 翻页（AC5.6）：{@code dir} 宽松解析，非 {@code received} 一律当 {@code sent}；页码越界夹到末页。 */
    @Transactional(readOnly = true)
    public UserGiftsTab page(long userId, String dir, int page) {
        String d = parseDir(dir);
        MapSqlParameterSource params = new MapSqlParameterSource("u", userId);
        Stats st = jdbc.queryForObject(STATS, params, (rs, i) -> new Stats(rs.getLong("sent_count"),
                rs.getLong("sent_total"), rs.getLong("recv_count"), rs.getLong("recv_qty"),
                rs.getLong("recv_senders"), rs.getLong("recv_pets"), rs.getString("recv_pet_name")));
        if (st == null) {
            st = new Stats(0, 0, 0, 0, 0, 0, null);
        }
        long count = UserGiftsTab.SENT.equals(d) ? st.sentCount() : st.recvCount();
        int totalPages = (int) ((count + PAGE_SIZE - 1) / PAGE_SIZE);
        int p = Math.max(0, Math.min(page, Math.max(totalPages - 1, 0)));
        List<GiftOrderAdminRow> rows = List.of();
        if (count > 0) {
            String where = UserGiftsTab.SENT.equals(d) ? SENT_WHERE : RECEIVED_WHERE;
            rows = jdbc.query(AdminGiftOrderQueryService.SELECT_ROWS + where + AdminGiftOrderQueryService.ORDER_BY
                            + " LIMIT :limit OFFSET :offset",
                    new MapSqlParameterSource("u", userId).addValue("limit", PAGE_SIZE)
                            .addValue("offset", (long) p * PAGE_SIZE),
                    AdminGiftOrderQueryService.ROW);
        }
        return new UserGiftsTab(userId, d, st.sentCount(), st.sentTotal(), st.recvCount(), st.recvQty(),
                st.recvSenders(), st.recvPets(), st.recvPetName(), rows, p, totalPages);
    }

    static String parseDir(String raw) {
        return raw != null && UserGiftsTab.RECEIVED.equalsIgnoreCase(raw.trim()) ? UserGiftsTab.RECEIVED
                : UserGiftsTab.SENT;
    }
}
