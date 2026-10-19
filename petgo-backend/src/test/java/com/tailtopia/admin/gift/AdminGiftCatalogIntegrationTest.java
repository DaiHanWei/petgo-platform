package com.tailtopia.admin.gift;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.authentication;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.tailtopia.admin.account.domain.AdminAccount;
import com.tailtopia.admin.account.domain.AdminAccountType;
import com.tailtopia.admin.account.domain.AdminPermissions;
import com.tailtopia.admin.account.repository.AdminAccountRepository;
import com.tailtopia.admin.service.AdminUserDetails;
import com.tailtopia.auth.domain.User;
import com.tailtopia.gift.domain.GiftCatalogItem;
import com.tailtopia.gift.domain.GiftKind;
import com.tailtopia.gift.dto.GiftSendRequest;
import com.tailtopia.gift.dto.GiftSendResponse;
import com.tailtopia.gift.repository.GiftCatalogRepository;
import com.tailtopia.gift.service.GiftSendService;
import com.tailtopia.pay.domain.PayChannel;
import com.tailtopia.pay.domain.PaymentPurpose;
import com.tailtopia.pay.service.PaymentIntentService;
import com.tailtopia.profile.domain.PetProfile;
import com.tailtopia.profile.domain.PetType;
import com.tailtopia.profile.repository.PetProfileRepository;
import com.tailtopia.shared.error.AppException;
import com.tailtopia.shared.media.AliyunOssClient;
import com.tailtopia.shared.pay.GatewayStatus;
import com.tailtopia.shared.pay.PaymentCallback;
import com.tailtopia.support.ApiIntegrationTest;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import javax.imageio.ImageIO;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.http.HttpHeaders;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.security.authentication.TestingAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.authority.SimpleGrantedAuthority;

/**
 * L1（需 Postgres + Redis）：后台礼物目录与送礼 QRIS 最低金额（V1.4.0 ③ gift Story 3.1）。
 *
 * <p>闭环：后台新建 → App 目录可见 → 下线 → 不可见（已有礼物墙不变）；改价不碰已发起的 QRIS 订单快照；
 * QRIS 最低金额服务端确认弹层 → 保存 → 只对送礼生效；只读权限页面形态 + 所有写端点 403；未授权 403。
 *
 * <p>🔴 {@code pricing_config} 是全库单行：本类改过 QRIS 最低金额，{@link #restoreQrisMin} 一律改回 10000，
 * 免得同库其他送礼用例按错误门槛跑。OSS 打桩（本地无凭证，勿配假 AK）。
 */
@Import(AdminGiftCatalogIntegrationTest.StubOss.class)
class AdminGiftCatalogIntegrationTest extends ApiIntegrationTest {

    /** 假对象存储：只替掉「把字节送出去」这一步，格式 / 大小 / 尺寸校验走真实代码。 */
    @TestConfiguration
    static class StubOss {
        @Bean
        @Primary
        AliyunOssClient stubOssClient(com.tailtopia.shared.media.MediaProperties props) {
            return new AliyunOssClient(props) {
                @Override
                public String putPublicObject(String objectKey, byte[] bytes, String contentType) {
                    return "https://cdn.test/" + objectKey;
                }
            };
        }
    }

    private static final String BASE = "/admin/gift-catalog";

    @Autowired
    private AdminAccountRepository adminAccounts;
    @Autowired
    private GiftCatalogRepository catalog;
    @Autowired
    private PetProfileRepository petProfiles;
    @Autowired
    private GiftSendService sendService;
    @Autowired
    private PaymentIntentService paymentIntents;
    @Autowired
    private JdbcTemplate jdbc;

    @BeforeEach
    @AfterEach
    void restoreQrisMin() {
        jdbc.update("UPDATE pricing_config SET gift_qris_min_amount = 10000 WHERE id = 1");
    }

    // ——————————————————— 夹具 ———————————————————

    private Authentication staffWith(String... permissions) {
        long n = SEQ.incrementAndGet();
        AdminAccount acc = adminAccounts.save(AdminAccount.newSuperAdmin(
                "gift-" + n + "@tailtopia.test", "礼物目录测试员", "{bcrypt}x"));
        AdminUserDetails principal = new AdminUserDetails(acc.getId(), null, acc.getLarkEmail(),
                acc.getPasswordHash(), AdminAccountType.STAFF);
        // ⚠️ ROLE_ADMIN 不能省：/admin/** 在 URL 层就要求它。
        List<GrantedAuthority> auths = new ArrayList<>();
        auths.add(new SimpleGrantedAuthority("ROLE_ADMIN"));
        for (String p : permissions) {
            auths.add(new SimpleGrantedAuthority(p));
        }
        return new TestingAuthenticationToken(principal, null, auths);
    }

    private Authentication editor() {
        return staffWith(AdminPermissions.GIFT_CATALOG_VIEW, AdminPermissions.GIFT_CATALOG_EDIT);
    }

    private Authentication viewer() {
        return staffWith(AdminPermissions.GIFT_CATALOG_VIEW);
    }

    private static MockMultipartFile png() throws Exception {
        BufferedImage img = new BufferedImage(112, 112, BufferedImage.TYPE_INT_ARGB);
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        ImageIO.write(img, "png", out);
        return new MockMultipartFile("iconFile", "gift.png", "image/png", out.toByteArray());
    }

    private static MockMultipartFile jpg() throws Exception {
        return new MockMultipartFile("iconFile", "gift.jpg", "image/jpeg", png().getBytes());
    }

    private String uniqueName() {
        return "Gift" + (SEQ.incrementAndGet() % 1_000_000_000L);
    }

    private PetProfile newPet(User owner) {
        return petProfiles.save(PetProfile.create(owner.getId(), PetType.CAT, "Mochi", null, null, null, null,
                "AG-" + SEQ.incrementAndGet()));
    }

    private Long giftIdByName(String nameEn) {
        return jdbc.queryForObject("SELECT id FROM gift_catalog WHERE name_en = ?", Long.class, nameEn);
    }

    private long auditCount(String action, String targetId) {
        return jdbc.queryForObject(
                "SELECT count(*) FROM admin_audit_logs WHERE action_type = ? AND target_id = ?", Long.class,
                action, targetId);
    }

    private String appCatalog(long viewerId, long petId) throws Exception {
        return mvc.perform(get("/api/v1/gifts/catalog").param("petId", String.valueOf(petId))
                        .header(HttpHeaders.AUTHORIZATION, userBearer(viewerId)))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
    }

    // ——————————————————— 新建 → App 可见；下线 → 不可见 ———————————————————

    @Test
    void editorCreatesGiftThatShowsInAppThenOfflineHidesIt() throws Exception {
        String name = uniqueName();
        mvc.perform(multipart(BASE).file(png()).with(authentication(editor())).with(csrf())
                        .header("HX-Request", "true")
                        .param("nameEn", name).param("nameId", "Tulang").param("price", "4321")
                        .param("forCat", "true").param("kind", "NORMAL").param("sortOrder", ""))
                .andExpect(status().isOk());
        Long id = giftIdByName(name);
        assertThat(jdbc.queryForObject("SELECT status FROM gift_catalog WHERE id = ?", String.class, id))
                .isEqualTo("ONLINE");
        assertThat(jdbc.queryForObject("SELECT icon_url FROM gift_catalog WHERE id = ?", String.class, id))
                .contains("/public/gift-icon/");
        assertThat(auditCount("GIFT_CATALOG_CREATED", String.valueOf(id))).isEqualTo(1L);

        User viewerUser = newUser();
        PetProfile pet = newPet(newUser());
        assertThat(appCatalog(viewerUser.getId(), pet.getId())).contains(name);

        mvc.perform(post(BASE + "/" + id + "/status").with(authentication(editor())).with(csrf())
                        .header("HX-Request", "true").param("online", "false"))
                .andExpect(status().isOk());
        assertThat(appCatalog(viewerUser.getId(), pet.getId())).doesNotContain(name);
        assertThat(auditCount("GIFT_CATALOG_OFFLINED", String.valueOf(id))).isEqualTo(1L);

        // 幂等：再下线一次不写审计
        mvc.perform(post(BASE + "/" + id + "/status").with(authentication(editor())).with(csrf())
                        .header("HX-Request", "true").param("online", "false"))
                .andExpect(status().isOk());
        assertThat(auditCount("GIFT_CATALOG_OFFLINED", String.valueOf(id))).isEqualTo(1L);
    }

    @Test
    void jpegAndMissingLevelAre422() throws Exception {
        String jpgName = uniqueName();
        String body = mvc.perform(multipart(BASE).file(jpg()).with(authentication(editor())).with(csrf())
                        .header("HX-Request", "true").header("HX-Target", "gift-drawer-err")
                        .header(HttpHeaders.ACCEPT_LANGUAGE, "zh-CN")
                        .param("nameEn", jpgName).param("nameId", "Tulang").param("price", "1000")
                        .param("kind", "NORMAL"))
                .andExpect(status().isUnprocessableEntity()).andReturn().getResponse().getContentAsString();
        assertThat(body).contains("礼物图标");

        mvc.perform(multipart(BASE).file(png()).with(authentication(editor())).with(csrf())
                        .header("HX-Request", "true")
                        .param("nameEn", uniqueName()).param("nameId", "Tulang").param("price", "1000")
                        .param("kind", "EXCLUSIVE"))
                .andExpect(status().isUnprocessableEntity());
        assertThat(jdbc.queryForObject("SELECT count(*) FROM gift_catalog WHERE name_en = ?", Long.class, jpgName))
                .isZero();
    }

    // ——————————————————— 改价不碰订单快照 ———————————————————

    @Test
    void priceChangeLeavesPendingQrisOrderSnapshotIntact() throws Exception {
        long n = SEQ.incrementAndGet();
        GiftCatalogItem g = catalog.save(GiftCatalogItem.create("ag" + n, "Gift" + n, "Hadiah" + n,
                "https://cdn/g.png", 5_000, false, false, false, GiftKind.NORMAL, null, 0));
        jdbc.update("UPDATE pricing_config SET gift_qris_min_amount = 5000 WHERE id = 1");
        User sender = newUser();
        PetProfile pet = newPet(newUser());
        GiftSendResponse r = sendService.send(sender.getId(), pet.getId(), new GiftSendRequest(g.getPublicToken(), 1,
                PayChannel.QRIS, "ag-" + n, "gift_page", null));

        mvc.perform(multipart(BASE + "/" + g.getId() + "/edit").with(authentication(editor())).with(csrf())
                        .header("HX-Request", "true")
                        .param("nameEn", g.getNameEn()).param("nameId", g.getNameId()).param("price", "8000")
                        .param("kind", "NORMAL").param("sortOrder", "0"))
                .andExpect(status().isOk());
        assertThat(jdbc.queryForObject("SELECT price FROM gift_catalog WHERE id = ?", Long.class, g.getId()))
                .isEqualTo(8_000L);

        paymentIntents.applyCallback(new PaymentCallback(r.payment().token(), "stub-" + r.payment().token(),
                GatewayStatus.PAID, Map.of()));
        assertThat(jdbc.queryForObject("SELECT total FROM gift_orders WHERE public_token = ?", Long.class,
                r.orderToken())).isEqualTo(5_000L);
        assertThat(jdbc.queryForObject("SELECT status FROM gift_orders WHERE public_token = ?", String.class,
                r.orderToken())).isEqualTo("DELIVERED");
    }

    // ——————————————————— QRIS 最低金额 ———————————————————

    @Test
    void qrisMinConfirmThenSaveAppliesOnlyToGifts() throws Exception {
        String modal = mvc.perform(post(BASE + "/qris-min/confirm").with(authentication(editor())).with(csrf())
                        .header("HX-Request", "true").param("amount", "15000"))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        assertThat(modal).contains("Rp10,000").contains("Rp15,000").contains("confirm-modal");
        assertThat(jdbc.queryForObject("SELECT gift_qris_min_amount FROM pricing_config WHERE id = 1", Long.class))
                .as("预检不写库").isEqualTo(10_000L);

        long logsBefore = jdbc.queryForObject(
                "SELECT count(*) FROM config_change_logs WHERE field = 'gift_qris_min_amount'", Long.class);
        mvc.perform(post(BASE + "/qris-min").with(authentication(editor())).with(csrf())
                        .header("HX-Request", "true").param("amount", "15000"))
                .andExpect(status().isOk());
        assertThat(jdbc.queryForObject("SELECT gift_qris_min_amount FROM pricing_config WHERE id = 1", Long.class))
                .isEqualTo(15_000L);
        assertThat(jdbc.queryForObject(
                "SELECT count(*) FROM config_change_logs WHERE field = 'gift_qris_min_amount'", Long.class))
                .isEqualTo(logsBefore + 1);

        // Rp10,000 的送礼 QRIS → 422 gift-qris-below-min
        long n = SEQ.incrementAndGet();
        GiftCatalogItem tenK = catalog.save(GiftCatalogItem.create("aq" + n, "Gift" + n, "Hadiah" + n,
                "https://cdn/g.png", 10_000, false, false, false, GiftKind.NORMAL, null, 0));
        User sender = newUser();
        PetProfile pet = newPet(newUser());
        try {
            sendService.send(sender.getId(), pet.getId(), new GiftSendRequest(tenK.getPublicToken(), 1,
                    PayChannel.QRIS, "aq-" + n, "gift_page", null));
            throw new AssertionError("Rp10,000 的送礼 QRIS 应被拒");
        } catch (AppException e) {
            assertThat(e.getType().toString()).endsWith("gift-qris-below-min");
        }
        // 同额的其他 QRIS 照常发起（只对送礼生效，不进通用 createIntent）
        var other = paymentIntents.createIntent(sender.getId(), PaymentPurpose.PAWCOIN_TOPUP, PayChannel.QRIS, 10_000,
                "IDR", "ag-other-" + n);
        assertThat(other.token()).isNotBlank();

        // 与当前值相同 → 422，不弹层
        mvc.perform(post(BASE + "/qris-min/confirm").with(authentication(editor())).with(csrf())
                        .header("HX-Request", "true").param("amount", "15000"))
                .andExpect(status().isUnprocessableEntity());
    }

    // ——————————————————— 只读 / 未授权 ———————————————————

    @Test
    void viewerSeesReadOnlyPageAndEveryWriteIs403() throws Exception {
        long n = SEQ.incrementAndGet();
        GiftCatalogItem g = catalog.save(GiftCatalogItem.create("av" + n, "Golden ribbon " + n, "Pita emas",
                "https://cdn/g.png", 1_000, false, false, false, GiftKind.EXCLUSIVE, 5, 0));
        String page = mvc.perform(get(BASE).with(authentication(viewer())))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        assertThat(page).doesNotContain("/admin/gift-catalog/new/drawer");
        assertThat(page).contains("data-notice=\"gift-qris-readonly\"");

        String drawer = mvc.perform(get(BASE + "/" + g.getId() + "/drawer").with(authentication(viewer()))
                        .header("HX-Request", "true"))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        assertThat(drawer).contains("data-gift-readonly").doesNotContain("giftEditForm");

        mvc.perform(get(BASE + "/new/drawer").with(authentication(viewer())).header("HX-Request", "true"))
                .andExpect(status().isForbidden());
        mvc.perform(multipart(BASE).file(png()).with(authentication(viewer())).with(csrf())
                        .param("nameEn", "X").param("nameId", "Y").param("price", "1000"))
                .andExpect(status().isForbidden());
        mvc.perform(multipart(BASE + "/" + g.getId() + "/edit").with(authentication(viewer())).with(csrf())
                        .param("nameEn", "X").param("nameId", "Y").param("price", "1000"))
                .andExpect(status().isForbidden());
        mvc.perform(post(BASE + "/" + g.getId() + "/status").with(authentication(viewer())).with(csrf())
                        .param("online", "false"))
                .andExpect(status().isForbidden());
        mvc.perform(post(BASE + "/qris-min/confirm").with(authentication(viewer())).with(csrf())
                        .param("amount", "15000"))
                .andExpect(status().isForbidden());
        mvc.perform(post(BASE + "/qris-min").with(authentication(viewer())).with(csrf()).param("amount", "15000"))
                .andExpect(status().isForbidden());
    }

    @Test
    void unauthorizedStaffGets403() throws Exception {
        mvc.perform(get(BASE).with(authentication(staffWith(AdminPermissions.USER_TAG_VIEW))))
                .andExpect(status().isForbidden());
    }
}
