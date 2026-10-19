package com.tailtopia.gift.service;

import com.tailtopia.gift.domain.GiftOrderStatus;
import com.tailtopia.gift.port.GiftPetDirectory;
import com.tailtopia.gift.port.GiftPetDirectory.GiftPet;
import com.tailtopia.gift.web.dto.GiftOrderPage;
import com.tailtopia.gift.web.dto.GiftOrderRow;
import com.tailtopia.shared.media.AliyunOssClient;
import com.tailtopia.shared.paging.KeysetCursor;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Service;

/**
 * 我的送礼记录（V1.4.0 gift Story 2.3 · AC1 · AD-10）：只读，只列<b>本人送出</b>的订单，{@code created_at DESC, id DESC}
 * keyset 分页（{@link KeysetCursor}），走 1.1 的索引 {@code ix_gift_orders_sender_created}。
 *
 * <p>🔴 只读：订单状态只经交付 / 退回服务改变，本类不写任何表。宠物只经 {@link GiftPetDirectory} 取（本类不碰宠物表）；
 * 一页内按<b>去重后的</b> petId 逐个取（每页至多 20 只，端口无批量方法，不为此改端口签名）。本类不打日志（AC1.8）。
 *
 * <p>列入的状态见 {@link #LISTED_STATUSES}：{@code EXPIRED} / {@code FAILED} 未付款、没扣钱，不算一笔送礼（拍板点 ②）。
 */
@Service
public class MyGiftOrderQuery {

    /** 每页条数（AC1.2）。 */
    public static final int PAGE_SIZE = 20;

    /** 记录页列入的状态（AC1.3）。不含 {@code EXPIRED} / {@code FAILED}。 */
    static final EnumSet<GiftOrderStatus> LISTED_STATUSES = EnumSet.of(GiftOrderStatus.PENDING,
            GiftOrderStatus.DELIVERED, GiftOrderStatus.UNDELIVERED_REFUND_PENDING,
            GiftOrderStatus.UNDELIVERED_REFUNDED, GiftOrderStatus.REFUND_MANUAL);

    /** 一页：只取展示需要的列（快照列，不 join 目录）；行值比较续页，与索引 (sender_id, created_at DESC, id DESC) 同序。 */
    static final String PAGE_SQL = """
            SELECT id, public_token, created_at, status, channel, quantity, total, pet_id,
                   gift_name_en, gift_name_id, gift_icon_url, unit_price
              FROM gift_orders
             WHERE sender_id = :senderId
               AND status IN (:statuses)
               AND (created_at, id) < (CAST(:afterCreatedAt AS TIMESTAMPTZ), CAST(:afterId AS BIGINT))
             ORDER BY created_at DESC, id DESC
             LIMIT :limit
            """;

    private static final RowMapper<OrderRow> ROW = (rs, i) -> new OrderRow(
            rs.getLong("id"),
            rs.getString("public_token"),
            rs.getTimestamp("created_at").toInstant(),
            rs.getString("status"),
            rs.getString("channel"),
            rs.getInt("quantity"),
            rs.getLong("total"),
            rs.getObject("pet_id", Long.class),
            rs.getString("gift_name_en"),
            rs.getString("gift_name_id"),
            rs.getString("gift_icon_url"),
            rs.getLong("unit_price"));

    private final NamedParameterJdbcTemplate jdbc;
    private final GiftPetDirectory pets;

    public MyGiftOrderQuery(NamedParameterJdbcTemplate jdbc, GiftPetDirectory pets) {
        this.jdbc = jdbc;
        this.pets = pets;
    }

    /**
     * 本人送礼记录一页。
     *
     * @param senderId JWT {@code sub}（本人）
     * @param after    上一页游标；首页 {@code null}
     */
    public GiftOrderPage page(long senderId, KeysetCursor after) {
        KeysetCursor from = after == null ? KeysetCursor.firstPage() : after;
        MapSqlParameterSource p = new MapSqlParameterSource("senderId", senderId)
                .addValue("statuses", LISTED_STATUSES.stream().map(Enum::name).toList())
                .addValue("afterCreatedAt", from.createdAt().atOffset(ZoneOffset.UTC))
                .addValue("afterId", from.id())
                .addValue("limit", PAGE_SIZE + 1);
        List<OrderRow> fetched = jdbc.query(PAGE_SQL, p, ROW);
        boolean hasMore = fetched.size() > PAGE_SIZE;
        List<OrderRow> page = hasMore ? fetched.subList(0, PAGE_SIZE) : fetched;
        if (page.isEmpty()) {
            return new GiftOrderPage(List.of(), null);
        }

        Map<Long, Optional<GiftPet>> petById = new HashMap<>();
        for (OrderRow r : page) {
            if (r.petId() != null) {
                petById.computeIfAbsent(r.petId(), pets::pet);
            }
        }

        List<GiftOrderRow> items = new ArrayList<>(page.size());
        for (OrderRow r : page) {
            GiftPet pet = r.petId() == null ? null : petById.get(r.petId()).orElse(null);
            GiftOrderRow.PetView petView = pet == null ? null
                    : new GiftOrderRow.PetView(pet.petId(), pet.name(),
                            pet.avatarUrl() == null ? null : AliyunOssClient.exifStrippedDeliveryUrl(pet.avatarUrl()));
            items.add(new GiftOrderRow(r.orderToken(), r.createdAt(), r.status(), r.channel(), r.quantity(), r.total(),
                    new GiftOrderRow.GiftView(r.giftNameEn(), r.giftNameId(), r.giftIconUrl(), r.unitPrice()),
                    petView, petView == null));
        }
        String next = null;
        if (hasMore) {
            OrderRow last = page.get(page.size() - 1);
            next = new KeysetCursor(last.createdAt(), last.id()).encode();
        }
        return new GiftOrderPage(items, next);
    }

    /** 订单原行（{@code id} 只作游标 tie-breaker，不下发）。 */
    record OrderRow(long id, String orderToken, Instant createdAt, String status, String channel, int quantity,
            long total, Long petId, String giftNameEn, String giftNameId, String giftIconUrl, long unitPrice) {
    }
}
