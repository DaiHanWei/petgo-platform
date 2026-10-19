package com.tailtopia.gift.repository;

import java.util.Map;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Repository;

/**
 * 礼物墙 / 粉丝榜两张展示汇总表的写入（V1.4.0 gift Story 1.1 · AD-3）。
 *
 * <p>🔴 只在 {@code GiftDeliveryService.deliver} 同事务里调用（汇总只含 DELIVERED）。两表无 JPA 实体（本 story 只写不读；
 * 读口在 Story 2.1），故用 JDBC upsert，不受 {@code ddl-auto=validate} 影响。
 */
@Repository
public class GiftPetSummaryRepository {

    /** 礼物墙：按 (宠物, 礼物, 成交单价) 累加个数；名称 / 图标保留首次快照，冲突时不覆盖。 */
    static final String UPSERT_WALL_SQL = """
            INSERT INTO gift_pet_wall (pet_id, gift_id, unit_price, gift_name_en, gift_name_id, gift_icon_url, count)
            VALUES (:petId, :giftId, :unitPrice, :nameEn, :nameId, :iconUrl, :quantity)
            ON CONFLICT (pet_id, gift_id, unit_price)
            DO UPDATE SET count = gift_pet_wall.count + EXCLUDED.count
            """;

    /** 粉丝榜：按 (宠物, 送礼人) 累加金额与个数，刷新最近送达时刻。 */
    static final String UPSERT_SUPPORTER_SQL = """
            INSERT INTO gift_pet_supporters (pet_id, sender_id, total_amount, gift_count, last_sent_at)
            VALUES (:petId, :senderId, :total, :quantity, now())
            ON CONFLICT (pet_id, sender_id)
            DO UPDATE SET total_amount = gift_pet_supporters.total_amount + EXCLUDED.total_amount,
                          gift_count = gift_pet_supporters.gift_count + EXCLUDED.gift_count,
                          last_sent_at = now()
            """;

    private final NamedParameterJdbcTemplate jdbc;

    public GiftPetSummaryRepository(NamedParameterJdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public void addToWall(long petId, long giftId, long unitPrice, String nameEn, String nameId, String iconUrl,
            int quantity) {
        jdbc.update(UPSERT_WALL_SQL, Map.of("petId", petId, "giftId", giftId, "unitPrice", unitPrice,
                "nameEn", nameEn, "nameId", nameId, "iconUrl", iconUrl, "quantity", quantity));
    }

    public void addToSupporters(long petId, long senderId, long total, int quantity) {
        jdbc.update(UPSERT_SUPPORTER_SQL, Map.of("petId", petId, "senderId", senderId, "total", total,
                "quantity", quantity));
    }
}
