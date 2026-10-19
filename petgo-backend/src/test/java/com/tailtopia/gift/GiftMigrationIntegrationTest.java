package com.tailtopia.gift;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.tailtopia.auth.domain.User;
import com.tailtopia.profile.domain.PetProfile;
import com.tailtopia.profile.domain.PetType;
import com.tailtopia.profile.repository.PetProfileRepository;
import com.tailtopia.support.ApiIntegrationTest;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * V1.4.0 gift Story 1.1 · AC1 / AC2 · L1（需 postgres + redis）：四表与 CHECK、删宠物后订单保留 / 汇总级联、
 * {@code pricing_config.gift_qris_min_amount} 默认 10000。
 */
class GiftMigrationIntegrationTest extends ApiIntegrationTest {

    @Autowired
    private JdbcTemplate jdbc;
    @Autowired
    private PetProfileRepository petProfiles;

    private long insertGift(String kind, Integer minLevel) {
        return jdbc.queryForObject("""
                INSERT INTO gift_catalog (public_token, name_en, name_id, icon_url, price, kind, min_level)
                VALUES (?, 'Bone', 'Tulang', 'https://cdn/b.png', 1000, ?, ?) RETURNING id""",
                Long.class, "gm" + SEQ.incrementAndGet(), kind, minLevel);
    }

    private long insertOrder(long senderId, long petId, long ownerId, long giftId, long unitPrice, int qty,
            long total) {
        return jdbc.queryForObject("""
                INSERT INTO gift_orders (public_token, sender_id, pet_id, owner_id, pet_name, gift_id, gift_name_en,
                    gift_name_id, gift_icon_url, unit_price, quantity, total, channel, status, source, idem_key)
                VALUES (?, ?, ?, ?, 'Mochi', ?, 'Bone', 'Tulang', 'https://cdn/b.png', ?, ?, ?, 'PAWCOIN', 'DELIVERED',
                    'diary_tab', ?) RETURNING id""",
                Long.class, "go" + SEQ.incrementAndGet(), senderId, petId, ownerId, giftId, unitPrice, qty, total,
                "GIFT:test:" + SEQ.incrementAndGet());
    }

    private PetProfile newPet(User owner) {
        return petProfiles.save(PetProfile.create(owner.getId(), PetType.CAT, "Mochi", null, null, null, null,
                "GM-" + SEQ.incrementAndGet()));
    }

    @Test
    void fourTablesExist() {
        List<String> tables = jdbc.queryForList("""
                SELECT table_name FROM information_schema.tables
                 WHERE table_schema = current_schema()
                   AND table_name IN ('gift_catalog', 'gift_orders', 'gift_pet_wall', 'gift_pet_supporters')""",
                String.class);
        assertThat(tables).containsExactlyInAnyOrder("gift_catalog", "gift_orders", "gift_pet_wall",
                "gift_pet_supporters");
    }

    @Test
    void catalogChecksRejectBadRows() {
        assertThatThrownBy(() -> insertGift("EXCLUSIVE", null)).as("专属无 min_level")
                .isInstanceOf(DataIntegrityViolationException.class);
        assertThatThrownBy(() -> insertGift("NORMAL", 5)).as("普通带 min_level")
                .isInstanceOf(DataIntegrityViolationException.class);
        assertThatThrownBy(() -> jdbc.update("""
                INSERT INTO gift_catalog (public_token, name_en, name_id, icon_url, price, kind)
                VALUES (?, 'x', 'x', 'u', 99, 'NORMAL')""", "gm" + SEQ.incrementAndGet()))
                .as("price < 100").isInstanceOf(DataIntegrityViolationException.class);
        assertThat(insertGift("EXCLUSIVE", 5)).isPositive();
        assertThat(insertGift("NORMAL", null)).isPositive();
    }

    @Test
    void orderTotalMustEqualUnitPriceTimesQuantity() {
        User sender = newUser();
        User owner = newUser();
        PetProfile pet = newPet(owner);
        long giftId = insertGift("NORMAL", null);
        assertThatThrownBy(() -> insertOrder(sender.getId(), pet.getId(), owner.getId(), giftId, 1000, 3, 2999))
                .isInstanceOf(DataIntegrityViolationException.class);
        assertThatThrownBy(() -> insertOrder(sender.getId(), pet.getId(), owner.getId(), giftId, 1000, 100, 100_000))
                .as("quantity 1..99").isInstanceOf(DataIntegrityViolationException.class);
        assertThat(insertOrder(sender.getId(), pet.getId(), owner.getId(), giftId, 1000, 3, 3000)).isPositive();
    }

    @Test
    void statusColumnFitsLongestValue() {
        Integer width = jdbc.queryForObject("""
                SELECT character_maximum_length FROM information_schema.columns
                 WHERE table_schema = current_schema() AND table_name = 'gift_orders' AND column_name = 'status'""",
                Integer.class);
        assertThat(width).isEqualTo(32);
    }

    @Test
    void deletingPetKeepsOrderAndCascadesSummaries() {
        User sender = newUser();
        User owner = newUser();
        PetProfile pet = newPet(owner);
        long giftId = insertGift("NORMAL", null);
        long orderId = insertOrder(sender.getId(), pet.getId(), owner.getId(), giftId, 1000, 2, 2000);
        jdbc.update("""
                INSERT INTO gift_pet_wall (pet_id, gift_id, unit_price, gift_name_en, gift_name_id, gift_icon_url, count)
                VALUES (?, ?, 1000, 'Bone', 'Tulang', 'u', 2)""", pet.getId(), giftId);
        jdbc.update("""
                INSERT INTO gift_pet_supporters (pet_id, sender_id, total_amount, gift_count, last_sent_at)
                VALUES (?, ?, 2000, 2, now())""", pet.getId(), sender.getId());

        jdbc.update("DELETE FROM pet_profiles WHERE id = ?", pet.getId());

        assertThat(jdbc.queryForObject("SELECT pet_id FROM gift_orders WHERE id = ?", Long.class, orderId))
                .as("订单保留、pet_id 置空").isNull();
        assertThat(jdbc.queryForObject("SELECT pet_name FROM gift_orders WHERE id = ?", String.class, orderId))
                .isEqualTo("Mochi");
        assertThat(jdbc.queryForObject("SELECT count(*) FROM gift_pet_wall WHERE pet_id = ?", Long.class,
                pet.getId())).isZero();
        assertThat(jdbc.queryForObject("SELECT count(*) FROM gift_pet_supporters WHERE pet_id = ?", Long.class,
                pet.getId())).isZero();
    }

    @Test
    void pricingHasGiftQrisMinAmountDefault() {
        assertThat(jdbc.queryForObject("SELECT gift_qris_min_amount FROM pricing_config WHERE id = 1", Long.class))
                .isEqualTo(10_000L);
        assertThatThrownBy(() -> jdbc.update("UPDATE pricing_config SET gift_qris_min_amount = 99 WHERE id = 1"))
                .isInstanceOf(DataIntegrityViolationException.class);
    }
}
