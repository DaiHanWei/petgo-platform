package com.tailtopia.shop.address;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.tailtopia.shared.error.AppException;
import com.tailtopia.shared.logging.LogSanitizer;
import com.tailtopia.shop.address.domain.AddressFields;
import com.tailtopia.shop.address.domain.ShippingAddress;
import com.tailtopia.shop.address.service.ShippingAddressService;
import com.tailtopia.support.ApiIntegrationTest;
import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * L1：地址簿（Story 2.1，FR-98）。
 *
 * <p>🔒 本表是 App 内<b>首个用户地址 PII 数据集</b>，故除了功能断言，
 * 还专门验「日志里不出现三项 PII 明文」。
 */
class ShippingAddressIntegrationTest extends ApiIntegrationTest {

    @Autowired
    private ShippingAddressService service;
    @Autowired
    private JdbcTemplate jdbc;

    /** 造一个真实 users 行（有 FK，不能凭空用 id）。 */
    private long seedUser() {
        long n = SEQ.incrementAndGet();
        jdbc.update("INSERT INTO users (nickname, status) VALUES (?, 'ACTIVE')", "addr" + n);
        return jdbc.queryForObject(
                "SELECT id FROM users WHERE nickname = ?", Long.class, "addr" + n);
    }

    private AddressFields fields(String receiver) {
        return new AddressFields(receiver, "08123456789", "DKI Jakarta", "Jakarta Selatan",
                "Kebayoran Baru", "Jl. Melawai IV No. 12", "12160", "Rumah");
    }

    // ---------- 默认地址的三条规则 ----------

    @Test
    @DisplayName("🔴 首个创建的地址自动设为默认（没有默认的地址簿在结算页是死胡同）")
    void firstAddressBecomesDefault() {
        long uid = seedUser();
        ShippingAddress a = service.create(uid, fields("Budi"));
        assertThat(a.isDefault()).isTrue();

        ShippingAddress b = service.create(uid, fields("Siti"));
        assertThat(b.isDefault()).as("第二条不该自动抢默认").isFalse();
    }

    @Test
    @DisplayName("🔴 删除默认地址 → 剩余中最近使用的一条自动升为默认")
    void deletingDefaultPromotesMostRecentlyUsed() throws Exception {
        long uid = seedUser();
        ShippingAddress first = service.create(uid, fields("Budi"));     // 自动默认
        ShippingAddress second = service.create(uid, fields("Siti"));
        ShippingAddress third = service.create(uid, fields("Andi"));

        // 让 second 成为「最近使用」
        service.markUsed(uid, third.getPublicToken());
        Thread.sleep(5);
        service.markUsed(uid, second.getPublicToken());

        service.delete(uid, first.getPublicToken());

        var list = service.list(uid);
        assertThat(list).hasSize(2);
        ShippingAddress promoted = list.stream().filter(ShippingAddress::isDefault)
                .findFirst().orElseThrow();
        assertThat(promoted.getPublicToken())
                .as("最近使用的一条应升为默认")
                .isEqualTo(second.getPublicToken());
    }

    @Test
    @DisplayName("删光地址簿 → 无默认（合法状态，不该抛错）")
    void emptyBookHasNoDefault() {
        long uid = seedUser();
        ShippingAddress only = service.create(uid, fields("Budi"));
        service.delete(uid, only.getPublicToken());
        assertThat(service.list(uid)).isEmpty();
    }

    @Test
    @DisplayName("🔴 一个用户至多一个默认（DB 部分唯一索引兜底，切换默认不产生两个）")
    void atMostOneDefault() {
        long uid = seedUser();
        ShippingAddress a = service.create(uid, fields("Budi"));
        ShippingAddress b = service.create(uid, fields("Siti"));

        service.setDefault(uid, b.getPublicToken());

        Integer defaults = jdbc.queryForObject(
                "SELECT count(*) FROM shipping_addresses WHERE user_id = ? AND is_default",
                Integer.class, uid);
        assertThat(defaults).isEqualTo(1);
        assertThat(service.require(uid, b.getPublicToken()).isDefault()).isTrue();
        assertThat(service.require(uid, a.getPublicToken()).isDefault()).isFalse();
    }

    // ---------- 上限 ----------

    @Test
    @DisplayName("🔴 地址簿上限 20：第 21 条返回明确领域错误，而不是静默失败")
    void twentyFirstAddressRejectedLoudly() {
        long uid = seedUser();
        for (int i = 0; i < service.maxPerUser(); i++) {
            service.create(uid, fields("R" + i));
        }
        assertThatThrownBy(() -> service.create(uid, fields("overflow")))
                .isInstanceOf(AppException.class)
                .hasMessageContaining("最多保存");

        Integer count = jdbc.queryForObject(
                "SELECT count(*) FROM shipping_addresses WHERE user_id = ?", Integer.class, uid);
        assertThat(count).isEqualTo(service.maxPerUser());
    }

    // ---------- 🔒 越权 ----------

    @Test
    @DisplayName("🔒 越权访问他人地址返回 404 而非 403（403 等于确认 token 存在，可用来枚举）")
    void crossUserAccessIsNotFound() {
        long owner = seedUser();
        long attacker = seedUser();
        ShippingAddress a = service.create(owner, fields("Budi"));

        for (var call : new Runnable[] {
            () -> service.require(attacker, a.getPublicToken()),
            () -> service.update(attacker, a.getPublicToken(), fields("Hack")),
            () -> service.delete(attacker, a.getPublicToken()),
            () -> service.setDefault(attacker, a.getPublicToken()),
        }) {
            assertThatThrownBy(call::run)
                    .isInstanceOf(AppException.class)
                    .hasMessageContaining("不存在");
        }
        // 攻击者的任何尝试都没能改动 owner 的数据
        assertThat(service.require(owner, a.getPublicToken()).getReceiverName()).isEqualTo("Budi");
    }

    // ---------- 手机号归一化落库 ----------

    @Test
    @DisplayName("手机号以 E.164 落库，且 DB CHECK 兜住脏值")
    void phoneStoredAsE164() {
        long uid = seedUser();
        ShippingAddress a = service.create(uid, fields("Budi"));
        assertThat(a.getReceiverPhone()).isEqualTo("+628123456789");

        // 绕过应用层直接写脏号 → DB 必须拒绝
        boolean rejected;
        try {
            jdbc.update("UPDATE shipping_addresses SET receiver_phone = '0812' WHERE id = ?",
                    a.getId());
            rejected = false;
        } catch (Exception e) {
            rejected = true;
        }
        assertThat(rejected).as("ck_shipping_addresses_phone 必须拦住脏号").isTrue();
    }

    // ---------- 🔒 NFR-5：日志不得出现三项 PII ----------

    @Test
    @DisplayName("🔒 LogSanitizer 把收件人姓名 / 履约电话 / 详细地址全部打码")
    void sanitizerMasksAddressPii() {
        String body = """
                {"receiverName":"Budi Santoso","receiverPhone":"+628123456789",
                 "addressLine":"Jl. Melawai IV No. 12","kodePos":"12160",
                 "kecamatan":"Kebayoran Baru"}
                """;
        String out = new LogSanitizer()
                .sanitize(body.getBytes(StandardCharsets.UTF_8), "application/json");

        assertThat(out)
                .as("三项 PII 明文一个都不许进日志（NFR-5）")
                .doesNotContain("Budi Santoso")
                .doesNotContain("+628123456789")
                .doesNotContain("Jl. Melawai IV No. 12");
        // Kecamatan 不是 PII —— 它是运费与服务范围的判定粒度，排障需要看得见
        assertThat(out).contains("Kebayoran Baru");
    }
}
