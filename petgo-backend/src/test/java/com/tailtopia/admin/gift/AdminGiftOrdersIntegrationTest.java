package com.tailtopia.admin.gift;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.authentication;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.tailtopia.admin.account.domain.AdminAccount;
import com.tailtopia.admin.account.domain.AdminAccountType;
import com.tailtopia.admin.account.domain.AdminPermissions;
import com.tailtopia.admin.account.repository.AdminAccountRepository;
import com.tailtopia.admin.service.AdminUserDetails;
import com.tailtopia.auth.domain.User;
import com.tailtopia.support.ApiIntegrationTest;
import java.io.ByteArrayInputStream;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.authentication.TestingAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.authority.SimpleGrantedAuthority;

/**
 * L1（需 Postgres + Redis）：后台送礼记录（V1.4.0 ③ gift Story 3.2 · AC1 ~ AC8）。
 *
 * <p>夹具直接 INSERT {@code gift_orders}（本 story 只读，不经送礼服务造单）：PawCoin 已送达、QRIS 待支付、QRIS 已过期、
 * QRIS 付款后宠物被删已退回、退回失败待重试、需人工各一笔；其中一笔的送礼人已注销。用唯一的送礼人 / 主人 id 隔离同库其他用例。
 */
class AdminGiftOrdersIntegrationTest extends ApiIntegrationTest {

    private static final String BASE = "/admin/gift-orders";

    @Autowired
    private AdminAccountRepository adminAccounts;
    @Autowired
    private JdbcTemplate jdbc;

    private User sender;
    private User deletedSender;
    private User owner;
    private long giftId;

    // ——————————————————— 夹具 ———————————————————

    private Authentication staffWith(String... permissions) {
        long n = SEQ.incrementAndGet();
        AdminAccount acc = adminAccounts.save(AdminAccount.newSuperAdmin(
                "giftorders-" + n + "@tailtopia.test", "送礼记录测试员", "{bcrypt}x"));
        AdminUserDetails principal = new AdminUserDetails(acc.getId(), null, acc.getLarkEmail(),
                acc.getPasswordHash(), AdminAccountType.STAFF);
        // ⚠️ ROLE_ADMIN 不能省：/admin/** 在 URL 层就要求它，少了拿到的是过滤链 403（方法门控没被验到）。
        List<GrantedAuthority> auths = new ArrayList<>();
        auths.add(new SimpleGrantedAuthority("ROLE_ADMIN"));
        for (String p : permissions) {
            auths.add(new SimpleGrantedAuthority(p));
        }
        return new TestingAuthenticationToken(principal, null, auths);
    }

    private Authentication viewer() {
        return staffWith(AdminPermissions.GIFT_ORDER_VIEW);
    }

    private static String tok() {
        return ("t" + SEQ.incrementAndGet() + "xxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxx").substring(0, 32);
    }

    private long intent(long user) {
        return jdbc.queryForObject("INSERT INTO payment_intents (public_token, user_id, purpose, channel, amount, currency,"
                + " status, version, created_at, updated_at) VALUES (?, ?, 'GIFT', 'QRIS', 20000, 'IDR', 'PAID', 0, now(), now())"
                + " RETURNING id", Long.class, tok(), user);
    }

    /** pet_id 恒为 NULL（宠物已删）：本页只读订单快照；宠物在场的显示口径由 L0 AdminGiftOrderQueryServiceTest 覆盖。 */
    private void order(long senderId, long ownerId, String petName, String channel, String status,
            String reason, int attempts, Long intentId, Instant at) {
        jdbc.update("""
                INSERT INTO gift_orders (public_token, sender_id, pet_id, owner_id, pet_name, gift_id, gift_name_en,
                    gift_name_id, gift_icon_url, unit_price, quantity, total, channel, payment_intent_id, status,
                    undelivered_reason, refund_attempts, source, idem_key, created_at, refunded_at)
                VALUES (?, ?, NULL, ?, ?, ?, 'Tennis ball', 'Bola tenis', 'https://cdn.test/g.png', 10000, 2, 20000,
                    ?, ?, ?, ?, ?, 'gift_page', ?, ?, ?)
                """, tok(), senderId, ownerId, petName, giftId, channel, intentId, status, reason, attempts,
                "GIFT:" + senderId + ":" + SEQ.incrementAndGet(), Timestamp.from(at),
                "UNDELIVERED_REFUNDED".equals(status) ? Timestamp.from(at.plusSeconds(120)) : null);
    }

    @BeforeEach
    void seed() {
        sender = newUser();
        deletedSender = newUser();
        owner = newUser();
        jdbc.update("UPDATE users SET nickname = 'Andi' WHERE id = ?", sender.getId());
        jdbc.update("UPDATE users SET nickname = 'Ghost', deleted_at = now() WHERE id = ?", deletedSender.getId());
        giftId = jdbc.queryForObject("""
                INSERT INTO gift_catalog (public_token, name_en, name_id, icon_url, price, kind, status)
                VALUES (?, 'Tennis ball', 'Bola tenis', 'https://cdn.test/g.png', 10000, 'NORMAL', 'ONLINE') RETURNING id
                """, Long.class, tok());
        Instant t = Instant.parse("2026-10-16T13:10:00Z");
        order(sender.getId(), owner.getId(), "Mochi", "PAWCOIN", "DELIVERED", null, 0, null, t);
        order(sender.getId(), owner.getId(), "Mochi", "QRIS", "PENDING", null, 0, intent(sender.getId()), t.plusSeconds(60));
        order(sender.getId(), owner.getId(), "Mochi", "QRIS", "EXPIRED", null, 0, intent(sender.getId()), t.plusSeconds(120));
        order(sender.getId(), owner.getId(), "Boba", "QRIS", "UNDELIVERED_REFUNDED", "PET_DELETED", 1,
                intent(sender.getId()), t.plusSeconds(180));
        order(sender.getId(), owner.getId(), "Boba", "QRIS", "UNDELIVERED_REFUND_PENDING", "SENDER_BLOCKED", 2,
                intent(sender.getId()), t.plusSeconds(240));
        order(deletedSender.getId(), owner.getId(), "Boba", "QRIS", "REFUND_MANUAL", "OWNER_DELETED", 5,
                intent(deletedSender.getId()), t.plusSeconds(300));
    }

    private String page(Authentication a, String... params) throws Exception {
        var req = get(BASE).param("lang", "zh_CN").with(authentication(a));
        for (int i = 0; i + 1 < params.length; i += 2) {
            req = req.param(params[i], params[i + 1]);
        }
        return mvc.perform(req).andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
    }

    // ——————————————————— 全站列表 ———————————————————

    @Test
    void ownerFilterListsAllSixWithSnapshotNamesAndMasksDeletedSender() throws Exception {
        String html = page(viewer(), "ownerId", String.valueOf(owner.getId()));
        assertThat(html).contains("送礼记录").contains("App 内所有送礼订单（PawCoin 与 QRIS）。只读，不提供改单。")
                .contains("Andi").contains("#" + sender.getId())
                .as("注销送礼人显示「（已注销）#id」，不显示注销前昵称").contains("（已注销）#" + deletedSender.getId())
                .doesNotContain("Ghost")
                .contains("（已删除）Boba").contains("Tennis ball")
                .contains("已送达").contains("待支付").contains("已过期").contains("已付款未送达")
                .contains("第 1 / 1 页（共 6 条）")
                .as("全站视图有支付意图列").contains("支付意图")
                .as("只读：页面无任何 POST 表单").doesNotContain("method=\"post\"");
        assertThat(html).as("导出按钮带全部当前筛选").contains("/admin/gift-orders/export.xlsx?senderId=&amp;ownerId=" + owner.getId());
    }

    @Test
    void undeliveredFilterSwitchesToAnomalyView() throws Exception {
        String html = page(viewer(), "ownerId", String.valueOf(owner.getId()), "status", "UNDELIVERED");
        assertThat(html).contains("第 1 / 1 页（共 3 条）")
                .contains("原因").contains("PawCoin 退回")
                .contains("宠物已删除").contains("送礼人已拉黑主人").contains("主人已注销")
                .contains("已退回 Rp20,000").contains("退回失败 · 待重试").contains("退回失败 · 需人工")
                .contains("已重试 2 次")
                .contains("退回失败的订单由系统每 10 分钟自动重试");
        assertThat(html).as("异常单摘要四格：3 / Rp60,000 / 1 / 2")
                .contains("data-sum=\"undelivered\">3<").contains("data-sum=\"amount\">Rp60,000<")
                .contains("data-sum=\"refunded\">1<").contains("data-sum=\"refund-failed\">2<");
    }

    @Test
    void filtersBySenderChannelAndDate() throws Exception {
        assertThat(page(viewer(), "senderId", String.valueOf(sender.getId()))).contains("（共 5 条）");
        assertThat(page(viewer(), "ownerId", String.valueOf(owner.getId()), "channel", "PAWCOIN")).contains("（共 1 条）");
        assertThat(page(viewer(), "ownerId", String.valueOf(owner.getId()), "channel", "bogus"))
                .as("不认识的渠道 = 不限").contains("（共 6 条）");
        assertThat(page(viewer(), "ownerId", String.valueOf(owner.getId()), "from", "2026-10-16", "to", "2026-10-16"))
                .as("止日含当天（WIB）").contains("（共 6 条）");
        assertThat(page(viewer(), "ownerId", String.valueOf(owner.getId()), "from", "2026-10-17"))
                .as("M3 无结果").contains("暂无符合筛选条件的送礼记录").contains("🎁");
    }

    // ——————————————————— 导出 ———————————————————

    @Test
    void exportMatchesScreenAndWritesOneAudit() throws Exception {
        long before = jdbc.queryForObject(
                "SELECT count(*) FROM admin_audit_logs WHERE action_type = 'GIFT_ORDER_LIST_EXPORTED'", Long.class);
        byte[] bytes = mvc.perform(get(BASE + "/export.xlsx").param("lang", "zh_CN")
                        .param("ownerId", String.valueOf(owner.getId())).param("status", "UNDELIVERED")
                        .with(authentication(viewer())))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsByteArray();
        Sheet s = new XSSFWorkbook(new ByteArrayInputStream(bytes)).getSheetAt(0);
        assertThat(s.getLastRowNum()).isEqualTo(3);
        Row h = s.getRow(0);
        assertThat(h.getCell(h.getLastCellNum() - 1).getStringCellValue()).isEqualTo("PawCoin 退回");
        assertThat(s.getRow(1).getCell(2).getStringCellValue()).isEqualTo("（已注销）#" + deletedSender.getId());
        assertThat(jdbc.queryForObject(
                "SELECT count(*) FROM admin_audit_logs WHERE action_type = 'GIFT_ORDER_LIST_EXPORTED'", Long.class))
                .isEqualTo(before + 1);
    }

    // ——————————————————— 权限 ———————————————————

    @Test
    void withoutGiftOrderViewIs403AndNavHidesTheItem() throws Exception {
        Authentication other = staffWith(AdminPermissions.PAYMENT_VIEW);
        mvc.perform(get(BASE).with(authentication(other))).andExpect(status().isForbidden());
        mvc.perform(get(BASE + "/export.xlsx").with(authentication(other))).andExpect(status().isForbidden());
        String payments = mvc.perform(get("/admin/payments").param("lang", "zh_CN").with(authentication(other)))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        assertThat(payments).doesNotContain("href=\"/admin/gift-orders\"");
        assertThat(page(viewer())).contains("href=\"/admin/gift-orders\"");
    }
}
