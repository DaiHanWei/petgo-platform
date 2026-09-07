package com.tailtopia.shop.order;

import static org.assertj.core.api.Assertions.assertThat;

import com.tailtopia.shop.address.domain.AddressFields;
import com.tailtopia.shop.address.service.ShippingAddressService;
import com.tailtopia.shop.order.domain.AddressSnapshot;
import com.tailtopia.shop.order.domain.ShopOrder;
import com.tailtopia.shop.order.repository.ShopOrderRepository;
import com.tailtopia.shop.service.ShopTokenGenerator;
import com.tailtopia.support.ApiIntegrationTest;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;

/** L1：订单持久化（Story 3.2）——建表、快照隔离、seq_no 不外露。 */
class ShopOrderPersistenceIntegrationTest extends ApiIntegrationTest {

    @Autowired
    private ShopOrderRepository orders;
    @Autowired
    private ShippingAddressService addresses;
    @Autowired
    private ShopTokenGenerator tokens;
    @Autowired
    private JdbcTemplate jdbc;

    private long seedUser() {
        long n = SEQ.incrementAndGet();
        jdbc.update("INSERT INTO users (nickname, status) VALUES (?, 'ACTIVE')", "ord" + n);
        return jdbc.queryForObject("SELECT id FROM users WHERE nickname = ?", Long.class, "ord" + n);
    }

    @Test
    @DisplayName("V109 已应用：八态 CHECK 生效，非法状态写不进去")
    void statusCheckRejectsUnknownValue() {
        long uid = seedUser();
        ShopOrder o = orders.save(ShopOrder.place(tokens.generate(), uid, 1000L, 0L, 0L,
                new AddressSnapshot("B", "+628123456789", "P", "K", "Kec", "Jl", "12160")));

        boolean rejected;
        try {
            jdbc.update("UPDATE shop_orders SET status = 'WHATEVER' WHERE id = ?", o.getId());
            rejected = false;
        } catch (Exception e) {
            rejected = true;
        }
        assertThat(rejected).as("ck_shop_orders_status 必须拦住未知态").isTrue();
    }

    @Test
    @DisplayName("🔴 地址是快照：改地址簿后，历史订单的履约地址一个字都不变（AD-13）")
    void addressSnapshotSurvivesAddressBookEdit() {
        long uid = seedUser();
        var saved = addresses.create(uid, new AddressFields("Budi", "08123456789",
                "DKI Jakarta", "Jakarta Selatan", "Kebayoran Baru",
                "Jl. Melawai IV No. 12", "12160", "Rumah"));

        ShopOrder o = orders.save(ShopOrder.place(tokens.generate(), uid, 1000L, 0L, 0L,
                new AddressSnapshot(saved.getReceiverName(), saved.getReceiverPhone(),
                        saved.getProvinsi(), saved.getKotaKabupaten(), saved.getKecamatan(),
                        saved.getAddressLine(), saved.getKodePos())));

        // 用户把地址簿改成完全不同的地址
        addresses.update(uid, saved.getPublicToken(), new AddressFields("Siti", "08987654321",
                "Jawa Barat", "Bandung", "Coblong", "Jl. Dago No. 99", "40135", "Kantor"));

        ShopOrder reloaded = orders.findById(o.getId()).orElseThrow();
        var ship = reloaded.shipTo();
        assertThat(ship.receiverName())
                .as("订单地址是履约凭证不是当前偏好——快递已按旧地址发出")
                .isEqualTo("Budi");
        assertThat(ship.addressLine()).isEqualTo("Jl. Melawai IV No. 12");
        assertThat(ship.kecamatan()).isEqualTo("Kebayoran Baru");
    }

    @Test
    @DisplayName("🔒 seq_no 自增且与 public_token 无关（对账用，绝不外露）")
    void seqNoIsInternalOnly() {
        long uid = seedUser();
        var a = orders.save(ShopOrder.place(tokens.generate(), uid, 1000L, 0L, 0L,
                new AddressSnapshot("B", "+628123456789", "P", "K", "Kec", "Jl", "12160")));
        var b = orders.saveAndFlush(ShopOrder.place(tokens.generate(), uid, 2000L, 0L, 0L,
                new AddressSnapshot("B", "+628123456789", "P", "K", "Kec", "Jl", "12160")));

        Long seqA = jdbc.queryForObject(
                "SELECT seq_no FROM shop_orders WHERE id = ?", Long.class, a.getId());
        Long seqB = jdbc.queryForObject(
                "SELECT seq_no FROM shop_orders WHERE id = ?", Long.class, b.getId());
        assertThat(seqB).isGreaterThan(seqA);
        // 🔴 token 里不含 seq_no —— 含了就等于把连续序号外露了
        assertThat(b.getPublicToken()).doesNotContain(String.valueOf(seqB));
    }

    @Test
    @DisplayName("🔒 越权查订单返回空（双条件查询，天然 404 而非 403）")
    void crossUserOrderLookupReturnsEmpty() {
        long owner = seedUser();
        long attacker = seedUser();
        ShopOrder o = orders.save(ShopOrder.place(tokens.generate(), owner, 1000L, 0L, 0L,
                new AddressSnapshot("B", "+628123456789", "P", "K", "Kec", "Jl", "12160")));

        assertThat(orders.findByPublicTokenAndUserId(o.getPublicToken(), attacker)).isEmpty();
        assertThat(orders.findByPublicTokenAndUserId(o.getPublicToken(), owner)).isPresent();
    }
}
