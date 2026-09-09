package com.tailtopia.admin.places;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.tailtopia.admin.account.domain.AdminPermissions;
import com.tailtopia.admin.account.domain.AdminRole;
import com.tailtopia.admin.account.service.AdminAccountService;
import com.tailtopia.admin.audit.repository.AdminAuditLogRepository;
import com.tailtopia.admin.audit.service.AuditActions;
import com.tailtopia.admin.places.domain.Place;
import com.tailtopia.admin.places.domain.PlacePhoto;
import com.tailtopia.admin.places.domain.PlaceReport;
import com.tailtopia.admin.places.domain.PlaceReportReason;
import com.tailtopia.admin.places.domain.PlaceReportStatus;
import com.tailtopia.admin.places.domain.PlaceStatus;
import com.tailtopia.admin.places.repository.PlacePhotoRepository;
import com.tailtopia.admin.places.repository.PlaceReportRepository;
import com.tailtopia.admin.places.repository.PlaceRepository;
import com.tailtopia.admin.places.service.PlaceTokenGenerator;
import com.tailtopia.admin.seed.dto.UploadedImage;
import com.tailtopia.admin.seed.service.AdminSeedImageService;
import com.tailtopia.admin.service.AdminUserDetails;
import com.tailtopia.admin.service.AdminUserDetailsService;
import com.tailtopia.auth.domain.User;
import com.tailtopia.support.ApiIntegrationTest;
import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.request.MockMultipartHttpServletRequestBuilder;

/**
 * L1（真库 + 真 Thymeleaf）：Story 5.4 新建场所（AC1 / AC2 / AC7）与 A1 场所举报页签（AC3～AC7）。
 * 照片上传链路（12-2 {@code AdminSeedImageService.upload}）用 MockitoBean 替身——本测试验证的是「只存 objectKey、uploader = 标记人、
 * photo_count = 张数」，不是 OSS 本身。
 */
class AdminPlaceCreateAndReportsIntegrationTest extends ApiIntegrationTest {

    @Autowired
    private PlaceRepository places;
    @Autowired
    private PlacePhotoRepository photos;
    @Autowired
    private PlaceReportRepository reports;
    @Autowired
    private PlaceTokenGenerator tokens;
    @Autowired
    private AdminAuditLogRepository audits;
    @Autowired
    private AdminAccountService accountService;
    @Autowired
    private AdminUserDetailsService userDetailsService;
    @MockitoBean
    private AdminSeedImageService images;

    private AdminUserDetails admin(String... perms) {
        long seq = SEQ.incrementAndGet();
        String email = "pc-" + seq + "@tailtopia.test";
        accountService.createAccount(email, "场所新建 " + seq, AdminRole.CUSTOM, List.of(perms), 970000L + seq);
        return userDetailsService.loadByEmail(email, false);
    }

    /** 发布身份池成员：启用中的虚拟账号（{@code selectableIdentities()} 第一段）。 */
    private User virtualMarker(String nickname) {
        long n = SEQ.incrementAndGet();
        return users.save(User.newVirtual("v-place-" + n, nickname, null, 1L));
    }

    private Place place(User marker, String name) {
        return places.save(Place.create(tokens.generate(), name, "CAFE", List.of("PET_FRIENDLY"), "desc", "Jakarta", "Jl. " + name,
                new BigDecimal("-6.208763"), new BigDecimal("106.845599"), marker.getId()));
    }

    private MockMultipartHttpServletRequestBuilder createReq(AdminUserDetails ops, String name, Long markerId, String lat, String lng,
            MockMultipartFile... files) {
        var b = multipart("/admin/places");
        for (MockMultipartFile f : files) {
            b.file(f);
        }
        if (name != null) {
            b.param("name", name);
        }
        b.param("placeType", "CAFE").param("city", "Jakarta").param("addressText", "Jl. Kemang Raya 1").param("tags", "pet_friendly outdoor")
                .param("description", "desc");
        if (lat != null) {
            b.param("lat", lat);
        }
        if (lng != null) {
            b.param("lng", lng);
        }
        if (markerId != null) {
            b.param("markerUserId", String.valueOf(markerId));
        }
        b.param("lang", "zh_CN").with(user(ops)).with(csrf()).header("HX-Request", "true");
        return b;
    }

    private static long badge(String html, String queue) {
        Matcher m = Pattern.compile("id=\"nav-badge-" + queue + "\"[^>]*>(\\d*)<").matcher(html);
        assertThat(m.find()).as("badge " + queue).isTrue();
        return m.group(1).isEmpty() ? 0 : Long.parseLong(m.group(1));
    }

    @Test
    void createFormRendersWithoutMapAndDeepLinkOpensIt() throws Exception {
        AdminUserDetails ops = admin(AdminPermissions.PLACE_MANAGE);
        User marker = virtualMarker("TailTopia Official");

        String form = mvc.perform(get("/admin/places/new/drawer").param("lang", "zh_CN").with(user(ops)).header("HX-Request", "true"))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        assertThat(form).contains("hx-post=\"/admin/places\"").contains("hx-encoding=\"multipart/form-data\"")
                .contains("name=\"lat\"").contains("name=\"lng\"").contains("type=\"number\"").contains("从 Google Maps")
                .contains("value=\"" + marker.getId() + "\" selected").contains("name=\"photos\"").contains("multiple").contains("data-max-files=\"9\"")
                .contains("list=\"places-create-cities\"")
                .doesNotContainIgnoringCase("leaflet").doesNotContainIgnoringCase("maplibre").doesNotContain("tile.openstreetmap");
        // 非 htmx 访问 → 302 回列表 ?create=1 → 整页带自动开抽屉标记
        mvc.perform(get("/admin/places/new/drawer").with(user(ops))).andExpect(status().is3xxRedirection())
                .andExpect(header().string("Location", "/admin/places?create=1"));
        String page = mvc.perform(get("/admin/places").param("create", "1").param("lang", "zh_CN").with(user(ops)))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        assertThat(page).contains("data-drawer-open=\"/admin/places/new/drawer\"").contains("data-drawer-autoopen=\"true\"")
                .contains("＋ 新建场所");
        // 无权限 403
        mvc.perform(get("/admin/places/new/drawer").with(user(admin(AdminPermissions.CONTENT_VIEW))).header("HX-Request", "true"))
                .andExpect(status().isForbidden());
    }

    @Test
    void createSavesPhotosTokenAuditAndRedirectsToOpenTheNewRow() throws Exception {
        AdminUserDetails ops = admin(AdminPermissions.PLACE_MANAGE);
        User marker = virtualMarker("TailTopia Official");
        when(images.upload(any(), anyString())).thenAnswer(inv -> new UploadedImage("https://cdn.test/x.jpg", 800, 600, null,
                inv.getArgument(1, String.class) + "/" + UUID.randomUUID() + ".jpg", 1234L));
        String name = "Kemang Dog Café " + UUID.randomUUID();

        String location = mvc.perform(createReq(ops, name, marker.getId(), "-6.2607", "106.8137",
                        new MockMultipartFile("photos", "a.jpg", "image/jpeg", new byte[] {1, 2, 3}),
                        new MockMultipartFile("photos", "b.jpg", "image/jpeg", new byte[] {4, 5, 6})))
                .andExpect(status().isOk()).andReturn().getResponse().getHeader("HX-Redirect");
        assertThat(location).startsWith("/admin/places?open=").doesNotContain("warn=");
        long id = Long.parseLong(location.substring("/admin/places?open=".length()));

        Place p = places.findById(id).orElseThrow();
        assertThat(p.getStatus()).isEqualTo(PlaceStatus.ACTIVE);
        assertThat(p.getPublicToken()).isNotBlank();
        assertThat(p.getMarkedByUserId()).isEqualTo(marker.getId());
        assertThat(p.getTags()).containsExactly("PET_FRIENDLY", "OUTDOOR");
        assertThat(p.getPhotoCount()).isEqualTo(2);
        List<PlacePhoto> saved = photos.findAll().stream().filter(x -> x.getPlaceId().equals(id)).toList();
        assertThat(saved).hasSize(2).allSatisfy(ph -> {
            assertThat(ph.getObjectKey()).startsWith("places/" + id + "/").doesNotStartWith("http"); // 只存 objectKey
            assertThat(ph.getUploaderUserId()).isEqualTo(marker.getId());
        });
        var audit = audits.findAllByOrderByIdAsc().stream()
                .filter(a -> AuditActions.PLACE_CREATED.equals(a.getActionType()) && String.valueOf(id).equals(a.getTargetId())).findFirst().orElseThrow();
        assertThat(audit.getSummary()).contains(name).contains("photos=2").doesNotContain("106.8137").doesNotContain("-6.2607");
        // 深链回列表：新行在首页且带抽屉 URL
        String page = mvc.perform(get("/admin/places").param("open", String.valueOf(id)).with(user(ops)))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        assertThat(page).contains("data-id=\"" + id + "\"").contains("/admin/places/" + id + "/drawer");
        // 非 htmx 提交 → 302（PRG）
        mvc.perform(createReq(ops, name + " 2", marker.getId(), "-6.2607", "106.8137").header("HX-Request", ""))
                .andExpect(status().is3xxRedirection());
    }

    @Test
    void createValidatesRequiredCoordinatesMarkerPoolAndWarnsOutsideJakarta() throws Exception {
        AdminUserDetails ops = admin(AdminPermissions.PLACE_MANAGE);
        User marker = virtualMarker("Ops Marker");
        when(images.validate(any())).thenAnswer(inv -> {
            org.springframework.web.multipart.MultipartFile f = inv.getArgument(0);
            if (f.getContentType() != null && f.getContentType().contains("heic")) {
                throw com.tailtopia.shared.error.AppException.validation("HEIC 不支持");
            }
            return f.getContentType();
        });
        int before = audits.findAllByOrderByIdAsc().size();

        // 必填缺失（名称）→ 422 行内 err
        String err = mvc.perform(createReq(ops, null, marker.getId(), "-6.2", "106.8")).andExpect(status().isUnprocessableEntity())
                .andReturn().getResponse().getContentAsString();
        assertThat(err).contains("inline-error").contains("名称");
        // 坐标越界 → 422（5.3 校验器）
        err = mvc.perform(createReq(ops, "X", marker.getId(), "-96.5", "106.8")).andExpect(status().isUnprocessableEntity())
                .andReturn().getResponse().getContentAsString();
        assertThat(err).contains("-90");
        // 标记人不在身份池（普通真实用户，未授权）→ 422
        err = mvc.perform(createReq(ops, "X", newUser().getId(), "-6.2", "106.8")).andExpect(status().isUnprocessableEntity())
                .andReturn().getResponse().getContentAsString();
        assertThat(err).contains("发布身份池");
        // 标记人缺失 → 422
        mvc.perform(createReq(ops, "X", null, "-6.2", "106.8")).andExpect(status().isUnprocessableEntity());
        // 禁用中的虚拟账号不在池内 → 422
        User disabled = virtualMarker("Disabled");
        disabled.setEnabled(false);
        users.save(disabled);
        mvc.perform(createReq(ops, "X", disabled.getId(), "-6.2", "106.8")).andExpect(status().isUnprocessableEntity());
        // 照片 > 9 → 422
        MockMultipartFile[] ten = new MockMultipartFile[10];
        for (int i = 0; i < 10; i++) {
            ten[i] = new MockMultipartFile("photos", "p" + i + ".jpg", "image/jpeg", new byte[] {1});
        }
        err = mvc.perform(createReq(ops, "X", marker.getId(), "-6.2", "106.8", ten)).andExpect(status().isUnprocessableEntity())
                .andReturn().getResponse().getContentAsString();
        assertThat(err).contains("照片最多 9 张");
        // 复审 #6：第 2 张 HEIC → 先整体校验，第 1 张不会先上传成 OSS 孤儿（upload 从未被调用）
        mvc.perform(createReq(ops, "X", marker.getId(), "-6.2", "106.8", new MockMultipartFile("photos", "a.jpg", "image/jpeg", new byte[] {1}),
                        new MockMultipartFile("photos", "b.heic", "image/heic", new byte[] {1}))).andExpect(status().isUnprocessableEntity());
        org.mockito.Mockito.verify(images, org.mockito.Mockito.never()).upload(any(), anyString());
        assertThat(audits.findAllByOrderByIdAsc().size()).isEqualTo(before); // 全部失败分支不落库不审计
        assertThat(places.findAll().stream().filter(p -> "X".equals(p.getName()) && p.getMarkedByUserId().equals(marker.getId()))).isEmpty();

        // 雅加达都会区外 → 创建成功 + HX-Redirect 带 warn；整页渲染黄条
        String location = mvc.perform(createReq(ops, "Bandung Park " + UUID.randomUUID(), marker.getId(), "-6.917", "107.619"))
                .andExpect(status().isOk()).andReturn().getResponse().getHeader("HX-Redirect");
        assertThat(location).contains("&warn=outsideJakarta");
        String page = mvc.perform(get("/admin/places").param("warn", "outsideJakarta").param("lang", "zh_CN").with(user(ops)))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        assertThat(page).contains("坐标不在雅加达都会区");
        // 无权限 403
        mvc.perform(createReq(admin(AdminPermissions.CONTENT_VIEW), "X", marker.getId(), "-6.2", "106.8")).andExpect(status().isForbidden());
    }

    @Test
    void placeReportsAggregatePerPlaceAndDismissAllSettlesThemAndRefreshesBadge() throws Exception {
        AdminUserDetails ops = admin(AdminPermissions.PLACE_MANAGE, AdminPermissions.CONTENT_TAKEDOWN);
        User marker = virtualMarker("Marker " + SEQ.incrementAndGet());
        Place p = place(marker, "Reported " + UUID.randomUUID());
        for (int i = 0; i < 3; i++) {
            reports.save(PlaceReport.create(p.getId(), newUser().getId(), i == 0 ? PlaceReportReason.CLOSED : PlaceReportReason.MISINFO));
        }
        String badgesBefore = mvc.perform(get("/admin/nav/badges").with(user(ops))).andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        long before = badge(badgesBefore, "manual-review");

        // AC3：A1 场所举报页签 —— 3 人举报同一场所 = 1 行，reporter_count=3；旧链接 ?type=PLACE_REPORT 也落到该页签
        String html = mvc.perform(get("/admin/manual-review").param("tab", "place").param("q", String.valueOf(marker.getId())).param("lang", "zh_CN")
                        .with(user(ops))).andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        assertThat(html).contains("id=\"review-row-place-" + p.getId() + "\"").contains("3 人 / 3 次").contains(p.getName())
                .contains("review-tab-count-place");
        assertThat(html.split("review-row-place-" + p.getId() + "\"")).hasSize(2);
        String legacy = mvc.perform(get("/admin/manual-review").param("type", "PLACE_REPORT").param("q", String.valueOf(marker.getId()))
                        .with(user(ops))).andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        assertThat(legacy).contains("review-row-place-" + p.getId());

        // AC4：右栏三卡（快照 / 举报列表 / 操作区）
        String detail = mvc.perform(get("/admin/manual-review/" + p.getId() + "/detail").param("tab", "place").param("lang", "zh_CN").with(user(ops)))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        assertThat(detail).contains(p.getName()).contains("/admin/places/" + p.getId() + "/delist").contains("/admin/places/" + p.getId() + "/reports/dismiss-all")
                .contains("name=\"from\" value=\"review\"")
                // confirm 文案的 {0} 由 admin-core.js 用 data-confirm-args 在浏览器端填（复审 #2）
                .contains("驳回该场所全部 {0} 条").contains("data-confirm-args=\"3\"").doesNotContain("/admin/content/" + p.getId());

        // 驳回：3 条 PENDING → DISMISSED，记 handled_by；审计 PLACE_REPORTS_DISMISSED；done fragment + 角标刷新
        String done = mvc.perform(post("/admin/places/" + p.getId() + "/reports/dismiss-all").param("from", "review").param("lang", "zh_CN")
                        .with(user(ops)).with(csrf()).header("HX-Request", "true").header("HX-Current-URL", "http://localhost/admin/manual-review?tab=place"))
                .andExpect(status().isOk()).andExpect(header().string("HX-Trigger", org.hamcrest.Matchers.containsString("admin:badge-refresh")))
                .andReturn().getResponse().getContentAsString();
        assertThat(done).contains("data-done").contains("review-row-place-" + p.getId()).contains("hx-swap-oob=\"delete\"").contains("已驳回 3 条举报");
        List<PlaceReport> all = reports.findByPlaceIdOrderByCreatedAtAsc(p.getId());
        assertThat(all).hasSize(3).allSatisfy(r -> {
            assertThat(r.getStatus()).isEqualTo(PlaceReportStatus.DISMISSED);
            assertThat(r.getHandledBy()).isEqualTo(ops.getAdminAccountId());
        });
        assertThat(places.findById(p.getId()).orElseThrow().getStatus()).isEqualTo(PlaceStatus.ACTIVE); // 驳回不动场所
        assertThat(audits.findAllByOrderByIdAsc().stream()
                .anyMatch(a -> AuditActions.PLACE_REPORTS_DISMISSED.equals(a.getActionType()) && String.valueOf(p.getId()).equals(a.getTargetId()))).isTrue();
        // 再驳回一次：0 条，不写审计
        int audited = audits.findAllByOrderByIdAsc().size();
        mvc.perform(post("/admin/places/" + p.getId() + "/reports/dismiss-all").param("from", "review").with(user(ops)).with(csrf()).header("HX-Request", "true"))
                .andExpect(status().isOk());
        assertThat(audits.findAllByOrderByIdAsc().size()).isEqualTo(audited);

        // AC5：角标同源——处置后 manual-review 角标 −1
        String badgesAfter = mvc.perform(get("/admin/nav/badges").with(user(ops))).andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        assertThat(badge(badgesAfter, "manual-review")).isEqualTo(before - 1);
        // 已处理态能看到（NO_ACTION 桶）
        String handled = mvc.perform(get("/admin/manual-review").param("tab", "place").param("state", "handled").param("q", String.valueOf(marker.getId()))
                        .with(user(ops))).andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        assertThat(handled).contains("review-row-place-" + p.getId());
    }

    @Test
    void delistFromReviewActionsPendingReportsAndMergedPlaceIsFlagged() throws Exception {
        AdminUserDetails ops = admin(AdminPermissions.PLACE_MANAGE, AdminPermissions.CONTENT_TAKEDOWN);
        User marker = virtualMarker("Marker " + SEQ.incrementAndGet());
        Place p = place(marker, "Delist " + UUID.randomUUID());
        reports.save(PlaceReport.create(p.getId(), newUser().getId(), PlaceReportReason.CLOSED));
        reports.save(PlaceReport.create(p.getId(), newUser().getId(), PlaceReportReason.INAPPROPRIATE));

        String done = mvc.perform(post("/admin/places/" + p.getId() + "/delist").param("from", "review").param("lang", "zh_CN")
                        .with(user(ops)).with(csrf()).header("HX-Request", "true").header("HX-Current-URL", "http://localhost/admin/manual-review?tab=place"))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        assertThat(done).contains("data-done").contains("已下架");
        assertThat(places.findById(p.getId()).orElseThrow().getStatus()).isEqualTo(PlaceStatus.DELISTED);
        // 复审 #9：已下架再从页签「下架」→ no-op，停留本条（detail fragment，不删行）
        String again = mvc.perform(post("/admin/places/" + p.getId() + "/delist").param("from", "review").param("lang", "zh_CN")
                        .with(user(ops)).with(csrf()).header("HX-Request", "true"))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        assertThat(again).doesNotContain("data-done").contains("没有改动");
        assertThat(reports.findByPlaceIdOrderByCreatedAtAsc(p.getId())).hasSize(2)
                .allSatisfy(r -> assertThat(r.getStatus()).isEqualTo(PlaceReportStatus.ACTIONED));
        var audit = audits.findAllByOrderByIdAsc().stream()
                .filter(a -> AuditActions.PLACE_DELISTED.equals(a.getActionType()) && String.valueOf(p.getId()).equals(a.getTargetId())).findFirst().orElseThrow();
        assertThat(audit.getSummary()).contains("reportsActioned=2");
        // 已处理态：RESOLVED 桶
        String handled = mvc.perform(get("/admin/manual-review").param("tab", "place").param("state", "handled").param("q", String.valueOf(marker.getId()))
                        .with(user(ops))).andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        assertThat(handled).contains("review-row-place-" + p.getId());

        // MERGED 场所仍有 PENDING 举报：左栏「已并入」徽标，右栏「下架」禁用、「驳回」可用
        Place keep = place(marker, "Keep " + UUID.randomUUID());
        Place merged = place(marker, "Merged " + UUID.randomUUID());
        reports.save(PlaceReport.create(merged.getId(), newUser().getId(), PlaceReportReason.DUPLICATE));
        Place m = places.findById(merged.getId()).orElseThrow();
        m.markMerged(keep.getId());
        places.save(m);
        String html = mvc.perform(get("/admin/manual-review").param("tab", "place").param("q", String.valueOf(marker.getId())).param("lang", "zh_CN")
                        .with(user(ops))).andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        assertThat(html).contains("review-row-place-" + merged.getId()).contains("已合并").doesNotContain("[MERGED]");
        String detail = mvc.perform(get("/admin/manual-review/" + merged.getId() + "/detail").param("tab", "place").param("lang", "zh_CN").with(user(ops)))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        assertThat(detail).contains("/admin/places/" + merged.getId() + "/reports/dismiss-all");
        assertThat(detail).containsPattern("<button[^>]*class=\"btn btn-danger\"[^>]*disabled");

        // D-40：无 place.manage 的复核员能看页签，按钮禁用并注明所缺权限；POST 403
        AdminUserDetails reviewer = admin(AdminPermissions.CONTENT_TAKEDOWN);
        String noPerm = mvc.perform(get("/admin/manual-review/" + merged.getId() + "/detail").param("tab", "place").param("lang", "zh_CN").with(user(reviewer)))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        assertThat(noPerm).doesNotContain("hx-post=\"/admin/places/" + merged.getId() + "/reports/dismiss-all\"").contains("需要");
        mvc.perform(post("/admin/places/" + merged.getId() + "/reports/dismiss-all").param("from", "review").with(user(reviewer)).with(csrf()).header("HX-Request", "true"))
                .andExpect(status().isForbidden());
    }
}
