package com.tailtopia.admin.places;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.tailtopia.admin.account.domain.AdminPermissions;
import com.tailtopia.admin.account.domain.AdminRole;
import com.tailtopia.admin.account.service.AdminAccountService;
import com.tailtopia.admin.places.domain.Place;
import com.tailtopia.admin.places.domain.PlaceAttitude;
import com.tailtopia.admin.places.domain.PlaceCheckin;
import com.tailtopia.admin.places.domain.PlaceComment;
import com.tailtopia.admin.places.domain.PlacePhoto;
import com.tailtopia.admin.places.domain.PlaceReport;
import com.tailtopia.admin.places.domain.PlaceReportReason;
import com.tailtopia.admin.places.repository.PlaceCheckinRepository;
import com.tailtopia.admin.places.repository.PlaceCommentRepository;
import com.tailtopia.admin.places.repository.PlacePhotoRepository;
import com.tailtopia.admin.places.repository.PlaceReportRepository;
import com.tailtopia.admin.places.repository.PlaceRepository;
import com.tailtopia.admin.places.service.PlaceTokenGenerator;
import com.tailtopia.admin.service.AdminUserDetails;
import com.tailtopia.admin.service.AdminUserDetailsService;
import com.tailtopia.auth.domain.User;
import com.tailtopia.support.ApiIntegrationTest;
import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

/**
 * L1（真库 + 真 Thymeleaf）：B6 场所列表与抽屉（V1.3.0 Story 5.2 AC7 四条 + AC2～AC6）：整页 200 含三行（ACTIVE / DELISTED / MERGED）；
 * 筛选状态只剩一行、关键词 ILIKE、城市下拉；摘要条四数字；HX-Request 返表格 fragment + 摘要 oob；抽屉 fragment 含照片 / 评论 / 打卡 /
 * 「已并入 →」；无权限 403；不存在 / 已删 id 404 fragment。
 */
class AdminPlaceIntegrationTest extends ApiIntegrationTest {

    @Autowired
    private PlaceRepository places;
    @Autowired
    private PlacePhotoRepository photos;
    @Autowired
    private PlaceCommentRepository comments;
    @Autowired
    private PlaceCheckinRepository checkins;
    @Autowired
    private PlaceReportRepository reports;
    @Autowired
    private PlaceTokenGenerator tokens;
    @Autowired
    private AdminAccountService accountService;
    @Autowired
    private AdminUserDetailsService userDetailsService;

    private AdminUserDetails admin(String... perms) {
        long seq = SEQ.incrementAndGet();
        String email = "pl-" + seq + "@tailtopia.test";
        accountService.createAccount(email, "场所 " + seq, AdminRole.CUSTOM, List.of(perms), 960000L + seq);
        return userDetailsService.loadByEmail(email, false);
    }

    private Place place(User marker, String name, String type, String city) {
        return places.save(Place.create(tokens.generate(), name, type, List.of("PET_FRIENDLY", "OUTDOOR", "WIFI", "PARKING"), "描述 " + name,
                city, "Jl. " + name + " 1", new BigDecimal("-6.208763"), new BigDecimal("106.845599"), marker.getId()));
    }

    @Test
    void listFiltersSummaryAndFragments() throws Exception {
        AdminUserDetails ops = admin(AdminPermissions.PLACE_MANAGE);
        User marker = newUser();
        User visitor = newUser();
        String tag = "K" + UUID.randomUUID().toString().substring(0, 8);
        Place active = place(marker, "Kopi " + tag, "CAFE", "Jakarta");
        Place delisted = place(marker, "Park " + tag, "PET_PARK", "Bandung");
        delisted.delist();
        places.save(delisted);
        Place merged = place(marker, "Dup " + tag, "CAFE", "Jakarta");
        merged.markMerged(active.getId());
        places.save(merged);
        photos.save(PlacePhoto.create(active.getId(), "places/" + active.getId() + "/a.jpg", visitor.getId()));
        comments.save(PlaceComment.create(active.getId(), visitor.getId(), "很友好 " + tag, PlaceAttitude.RECOMMEND));
        checkins.save(PlaceCheckin.create(active.getId(), visitor.getId()));
        reports.save(PlaceReport.create(active.getId(), visitor.getId(), PlaceReportReason.DUPLICATE));
        Place fresh = places.findById(active.getId()).orElseThrow();
        fresh.recount(1, 1, 1, 1, 0);
        places.save(fresh);

        // 整页：三行 + 侧栏项 + 摘要条 + 城市下拉
        String page = mvc.perform(get("/admin/places").param("q", tag).param("lang", "zh_CN").with(user(ops)))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        assertThat(page).contains("id=\"places-rows\"").contains("id=\"places-summary\"").contains("id=\"places-drawer\"")
                .contains("data-id=\"" + active.getId() + "\"").contains("data-id=\"" + delisted.getId() + "\"").contains("data-id=\"" + merged.getId() + "\"")
                .contains("Kopi " + tag).contains("咖啡店").contains("宠物公园").contains("已下架").contains("已合并").contains("上架")
                .contains("PET_FRIENDLY").contains("+1") // 标签最多 3 个 + N
                .contains("Jakarta").contains("Bandung").contains("/admin/places\"").contains("场所管理")
                .contains("/admin/manual-review?type=PLACE_REPORT");
        // 摘要条随筛选联动：上架 1（active）、待处理举报 1、累计打卡 1（recount 后）
        assertThat(page).containsPattern("上架场所</div>\\s*<div class=\"sum-value\">1<").containsPattern(">1</a>");

        // 筛选状态 → 只剩一行；关键词 ILIKE 地址；城市
        String only = mvc.perform(get("/admin/places").param("q", tag).param("status", "DELISTED").with(user(ops)).header("HX-Request", "true"))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        assertThat(only).contains("data-id=\"" + delisted.getId() + "\"").doesNotContain("data-id=\"" + active.getId() + "\"")
                .doesNotContain("<html").contains("id=\"places-summary\" hx-swap-oob=\"true\"");
        String byAddress = mvc.perform(get("/admin/places").param("q", "jl. dup " + tag.toLowerCase()).with(user(ops)).header("HX-Request", "true"))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        assertThat(byAddress).contains("data-id=\"" + merged.getId() + "\"").doesNotContain("data-id=\"" + active.getId() + "\"");
        String byCity = mvc.perform(get("/admin/places").param("q", tag).param("city", "Bandung").with(user(ops)).header("HX-Request", "true"))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        assertThat(byCity).contains("data-id=\"" + delisted.getId() + "\"").doesNotContain("data-id=\"" + merged.getId() + "\"");
        String none = mvc.perform(get("/admin/places").param("q", "no-such-" + tag).param("lang", "zh_CN").with(user(ops)).header("HX-Request", "true"))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        assertThat(none).contains("无匹配场所");

        // 抽屉：照片（签名 URL）/ 评论 / 打卡 / 标记人；MERGED 行「已并入 →」
        String drawer = mvc.perform(get("/admin/places/" + active.getId() + "/drawer").param("lang", "zh_CN").with(user(ops)).header("HX-Request", "true"))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        assertThat(drawer).contains("data-place-id=\"" + active.getId() + "\"").contains("Kopi " + tag).contains("很友好 " + tag).contains("👍")
                .contains("data-photo-id=") // 有 OSS 凭证 → data-lightbox 缩略图；无凭证（本地 / CI）→ 「图片暂不可用」占位，抽屉照常打开
                .contains("id=\"places-drawer-comments\"").contains("Jakarta").contains("-6.208763")
                .contains("disabled").doesNotContain("<html");
        String mergedDrawer = mvc.perform(get("/admin/places/" + merged.getId() + "/drawer").param("lang", "zh_CN").with(user(ops)).header("HX-Request", "true"))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        assertThat(mergedDrawer).contains("已并入").contains("/admin/places?open=" + active.getId()).contains("Kopi " + tag);
        // 评论翻页 fragment
        String commentsFrag = mvc.perform(get("/admin/places/" + active.getId() + "/drawer").param("part", "comments").param("commentPage", "0")
                        .with(user(ops)).header("HX-Request", "true"))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        assertThat(commentsFrag).contains("id=\"places-drawer-comments\"").doesNotContain("data-place-id").doesNotContain("data-photo-id");
        // 整页首屏只有一条摘要条；htmx 分支带 oob 摘要条
        assertThat(page.split("id=\"places-summary\"").length - 1).isEqualTo(1);
        // 非 htmx 访问抽屉 → 回整页深链
        mvc.perform(get("/admin/places/" + active.getId() + "/drawer").with(user(ops))).andExpect(status().is3xxRedirection());
    }

    @Test
    void forbiddenAndNotFound() throws Exception {
        AdminUserDetails ops = admin(AdminPermissions.PLACE_MANAGE);
        AdminUserDetails viewer = admin(AdminPermissions.CONTENT_VIEW);
        mvc.perform(get("/admin/places").with(user(viewer))).andExpect(status().isForbidden());
        mvc.perform(get("/admin/places/1/drawer").with(user(viewer)).header("HX-Request", "true")).andExpect(status().isForbidden());

        String nf = mvc.perform(get("/admin/places/999999999/drawer").param("lang", "zh_CN").with(user(ops)).header("HX-Request", "true"))
                .andExpect(status().isNotFound()).andReturn().getResponse().getContentAsString();
        assertThat(nf).contains("inline-error").contains("场所不存在");
        // 已软删 → 404
        Place gone = place(newUser(), "Gone " + UUID.randomUUID(), "CAFE", "Jakarta");
        gone.softDelete();
        places.save(gone);
        mvc.perform(get("/admin/places/" + gone.getId() + "/drawer").with(user(ops)).header("HX-Request", "true")).andExpect(status().isNotFound());
        String list = mvc.perform(get("/admin/places").param("q", gone.getName()).with(user(ops)).header("HX-Request", "true"))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        assertThat(list).doesNotContain("data-id=\"" + gone.getId() + "\"");
    }
}
