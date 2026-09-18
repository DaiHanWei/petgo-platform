package com.tailtopia.admin.places;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.tailtopia.admin.account.domain.AdminPermissions;
import com.tailtopia.admin.account.domain.AdminRole;
import com.tailtopia.admin.account.service.AdminAccountService;
import com.tailtopia.admin.audit.repository.AdminAuditLogRepository;
import com.tailtopia.admin.audit.service.AuditActions;
import com.tailtopia.admin.places.domain.Place;
import com.tailtopia.admin.places.domain.PlaceAttitude;
import com.tailtopia.admin.places.domain.PlaceCheckin;
import com.tailtopia.admin.places.domain.PlaceComment;
import com.tailtopia.admin.places.domain.PlacePhoto;
import com.tailtopia.admin.places.domain.PlaceReport;
import com.tailtopia.admin.places.domain.PlaceReportReason;
import com.tailtopia.admin.places.domain.PlaceStatus;
import com.tailtopia.admin.places.event.PlaceMergedEvent;
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
import org.springframework.test.context.event.ApplicationEvents;
import org.springframework.test.context.event.RecordApplicationEvents;

/**
 * L1（真库 + 真 Thymeleaf）：场所处置六端点（V1.3.0 Story 5.3 AC1～AC6）：编辑（越界 422 / 雅加达外 200 + 黄条 / 审计只记字段名）、
 * 下架（幂等不写审计）/ 恢复、删照片 / 删评论扣计数（审计不记正文）、合并全流程（子表归属、B 状态、A 计数、举报不迁移、事件字段、审计）、
 * 合并 MERGED 场所 422、无权限 403。事务回滚见 {@code PlaceMergeRollbackIntegrationTest}。
 */
@RecordApplicationEvents
class AdminPlaceActionsIntegrationTest extends ApiIntegrationTest {

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
    private AdminAuditLogRepository audits;
    @Autowired
    private AdminAccountService accountService;
    @Autowired
    private AdminUserDetailsService userDetailsService;
    @Autowired
    private ApplicationEvents events;

    private AdminUserDetails admin(String... perms) {
        long seq = SEQ.incrementAndGet();
        String email = "pa-" + seq + "@tailtopia.test";
        accountService.createAccount(email, "场所处置 " + seq, AdminRole.CUSTOM, List.of(perms), 960000L + seq);
        return userDetailsService.loadByEmail(email, false);
    }

    private Place place(User marker, String name) {
        return places.save(Place.create(tokens.generate(), name, "CAFE", List.of("PET_FRIENDLY"), "desc", "Jakarta", "Jl. " + name,
                new BigDecimal("-6.208763"), new BigDecimal("106.845599"), marker.getId()));
    }

    private boolean audited(String action, String targetId) {
        return audits.findAllByOrderByIdAsc().stream().anyMatch(a -> action.equals(a.getActionType()) && targetId.equals(a.getTargetId()));
    }

    @Test
    void editValidatesAuditsFieldNamesAndWarnsOutsideJakarta() throws Exception {
        AdminUserDetails ops = admin(AdminPermissions.PLACE_MANAGE);
        Place p = place(newUser(), "Kopi " + UUID.randomUUID());

        // 越界 422（UI 稿 2-22：纬度 -96.5）
        String err = mvc.perform(post("/admin/places/" + p.getId() + "/edit").param("name", "X").param("placeType", "CAFE").param("city", "Jakarta")
                        .param("addressText", "Jl. 1").param("lat", "-96.5").param("lng", "106.8").param("lang", "zh_CN")
                        .with(user(ops)).with(csrf()).header("HX-Request", "true"))
                .andExpect(status().isUnprocessableEntity()).andReturn().getResponse().getContentAsString();
        assertThat(err).contains("inline-error").contains("-90");
        // 雅加达外 → 200 + 黄条；审计只记字段名，不记坐标 / 地址
        String ok = mvc.perform(post("/admin/places/" + p.getId() + "/edit").param("name", "Kopi Bandung").param("placeType", "PET_PARK")
                        .param("tags", "outdoor, wifi").param("description", "新描述").param("city", "Bandung").param("addressText", "Jl. Braga 9")
                        .param("lat", "-6.917").param("lng", "107.619").param("lang", "zh_CN")
                        .with(user(ops)).with(csrf()).header("HX-Request", "true"))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        assertThat(ok).contains("data-place-id=\"" + p.getId() + "\"").contains("坐标不在雅加达都会区").contains("Kopi Bandung").contains("宠物公园")
                .contains("OUTDOOR").contains("id=\"places-row-" + p.getId() + "\"").contains("hx-swap-oob");
        Place saved = places.findById(p.getId()).orElseThrow();
        assertThat(saved.getCity()).isEqualTo("Bandung");
        assertThat(saved.getTags()).containsExactly("OUTDOOR", "WIFI");
        assertThat(saved.getMarkedByUserId()).isEqualTo(p.getMarkedByUserId());
        var audit = audits.findAllByOrderByIdAsc().stream()
                .filter(a -> AuditActions.PLACE_EDITED.equals(a.getActionType()) && String.valueOf(p.getId()).equals(a.getTargetId())).findFirst().orElseThrow();
        assertThat(audit.getSummary()).contains("name").contains("lat").contains("city").doesNotContain("Braga").doesNotContain("107.619");
        // 无改动 → 200 不写审计
        int before = audits.findAllByOrderByIdAsc().size();
        mvc.perform(post("/admin/places/" + p.getId() + "/edit").param("name", "Kopi Bandung").param("placeType", "PET_PARK").param("tags", "outdoor, wifi")
                        .param("description", "新描述").param("city", "Bandung").param("addressText", "Jl. Braga 9").param("lat", "-6.917").param("lng", "107.619")
                        .with(user(ops)).with(csrf()).header("HX-Request", "true"))
                .andExpect(status().isOk());
        assertThat(audits.findAllByOrderByIdAsc().size()).isEqualTo(before);
    }

    @Test
    void delistRestoreIdempotentAndRemovePhotoComment() throws Exception {
        AdminUserDetails ops = admin(AdminPermissions.PLACE_MANAGE);
        User visitor = newUser();
        Place p = place(newUser(), "Kopi " + UUID.randomUUID());
        PlacePhoto ph = photos.save(PlacePhoto.create(p.getId(), "places/x.jpg", visitor.getId()));
        PlaceComment c1 = comments.save(PlaceComment.create(p.getId(), visitor.getId(), "推荐！", PlaceAttitude.RECOMMEND));
        comments.save(PlaceComment.create(p.getId(), visitor.getId(), "不推荐", PlaceAttitude.NOT_RECOMMEND));
        Place fresh = places.findById(p.getId()).orElseThrow();
        fresh.recount(1, 2, 0, 1, 1);
        places.save(fresh);

        String delisted = mvc.perform(post("/admin/places/" + p.getId() + "/delist").param("lang", "zh_CN").with(user(ops)).with(csrf()).header("HX-Request", "true"))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        assertThat(delisted).contains("已下架").contains("id=\"places-row-" + p.getId() + "\"");
        assertThat(places.findById(p.getId()).orElseThrow().getStatus()).isEqualTo(PlaceStatus.DELISTED);
        assertThat(audited(AuditActions.PLACE_DELISTED, String.valueOf(p.getId()))).isTrue();
        int before = audits.findAllByOrderByIdAsc().size();
        mvc.perform(post("/admin/places/" + p.getId() + "/delist").with(user(ops)).with(csrf()).header("HX-Request", "true")).andExpect(status().isOk());
        assertThat(audits.findAllByOrderByIdAsc().size()).isEqualTo(before); // 幂等不写审计
        mvc.perform(post("/admin/places/" + p.getId() + "/restore").with(user(ops)).with(csrf()).header("HX-Request", "true")).andExpect(status().isOk());
        assertThat(places.findById(p.getId()).orElseThrow().getStatus()).isEqualTo(PlaceStatus.ACTIVE);
        assertThat(audited(AuditActions.PLACE_RESTORED, String.valueOf(p.getId()))).isTrue();

        // 删照片 → photo_count −1（允许到 0）
        mvc.perform(post("/admin/places/" + p.getId() + "/photos/" + ph.getId() + "/remove").with(user(ops)).with(csrf()).header("HX-Request", "true"))
                .andExpect(status().isOk());
        assertThat(photos.findById(ph.getId()).orElseThrow().isDeleted()).isTrue();
        assertThat(places.findById(p.getId()).orElseThrow().getPhotoCount()).isZero();
        assertThat(audited(AuditActions.PLACE_PHOTO_REMOVED, String.valueOf(ph.getId()))).isTrue();
        // 删评论 → comment_count −1、recommend_count −1；审计不记正文
        mvc.perform(post("/admin/places/" + p.getId() + "/comments/" + c1.getId() + "/remove").with(user(ops)).with(csrf()).header("HX-Request", "true"))
                .andExpect(status().isOk());
        Place after = places.findById(p.getId()).orElseThrow();
        assertThat(after.getCommentCount()).isEqualTo(1);
        assertThat(after.getRecommendCount()).isZero();
        assertThat(after.getNotRecommendCount()).isEqualTo(1);
        var audit = audits.findAllByOrderByIdAsc().stream()
                .filter(a -> AuditActions.PLACE_COMMENT_REMOVED.equals(a.getActionType()) && String.valueOf(c1.getId()).equals(a.getTargetId())).findFirst().orElseThrow();
        assertThat(audit.getSummary()).doesNotContain("推荐！");
        // 不属于该场所 → 404 fragment
        Place other = place(newUser(), "Other " + UUID.randomUUID());
        mvc.perform(post("/admin/places/" + other.getId() + "/photos/" + ph.getId() + "/remove").with(user(ops)).with(csrf()).header("HX-Request", "true"))
                .andExpect(status().isNotFound());
        // 无权限 403
        AdminUserDetails viewer = admin(AdminPermissions.CONTENT_VIEW);
        mvc.perform(post("/admin/places/" + p.getId() + "/delist").with(user(viewer)).with(csrf()).header("HX-Request", "true")).andExpect(status().isForbidden());
    }

    @Test
    void mergeMovesChildrenMarksMergedRecountsAndPublishesEvent() throws Exception {
        AdminUserDetails ops = admin(AdminPermissions.PLACE_MANAGE);
        User visitor = newUser();
        Place keep = place(newUser(), "Keep " + UUID.randomUUID());
        Place dup = place(newUser(), "Dup " + UUID.randomUUID());
        photos.save(PlacePhoto.create(dup.getId(), "places/d.jpg", visitor.getId()));
        comments.save(PlaceComment.create(dup.getId(), visitor.getId(), "dup 评论", PlaceAttitude.RECOMMEND));
        checkins.save(PlaceCheckin.create(dup.getId(), visitor.getId()));
        comments.save(PlaceComment.create(keep.getId(), visitor.getId(), "keep 评论", PlaceAttitude.NOT_RECOMMEND));
        PlaceReport report = reports.save(PlaceReport.create(dup.getId(), visitor.getId(), PlaceReportReason.DUPLICATE));

        // 候选搜索：排除自身与非 ACTIVE
        String candidates = mvc.perform(get("/admin/places/" + dup.getId() + "/merge-candidates").param("q", keep.getName()).with(user(ops)).header("HX-Request", "true"))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        assertThat(candidates).contains("name=\"keepId\"").contains("value=\"" + keep.getId() + "\"").doesNotContain("value=\"" + dup.getId() + "\"");

        String html = mvc.perform(post("/admin/places/" + dup.getId() + "/merge").param("keepId", String.valueOf(keep.getId())).param("lang", "zh_CN")
                        .with(user(ops)).with(csrf()).header("HX-Request", "true"))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        assertThat(html).contains("已合并").contains("已并入").contains(keep.getName());

        Place mergedRow = places.findById(dup.getId()).orElseThrow();
        assertThat(mergedRow.getStatus()).isEqualTo(PlaceStatus.MERGED);
        assertThat(mergedRow.getMergedIntoId()).isEqualTo(keep.getId());
        Place keepRow = places.findById(keep.getId()).orElseThrow();
        assertThat(keepRow.getPhotoCount()).isEqualTo(1);
        assertThat(keepRow.getCommentCount()).isEqualTo(2);
        assertThat(keepRow.getCheckinCount()).isEqualTo(1);
        assertThat(keepRow.getRecommendCount()).isEqualTo(1);
        assertThat(keepRow.getNotRecommendCount()).isEqualTo(1);
        assertThat(photos.countByPlaceIdAndDeletedAtIsNull(dup.getId())).isZero();
        assertThat(checkins.countByPlaceId(keep.getId())).isEqualTo(1);
        // 举报不迁移，仍指向 B
        assertThat(reports.findById(report.getId()).orElseThrow().getPlaceId()).isEqualTo(dup.getId());
        assertThat(audited(AuditActions.PLACE_MERGED, String.valueOf(dup.getId()))).isTrue();
        assertThat(events.stream(PlaceMergedEvent.class).filter(e -> e.mergedPlaceId() == dup.getId()).findFirst().orElseThrow().keepPlaceId())
                .isEqualTo(keep.getId());

        // 已 MERGED 再合并 / 再下架 → 422；自合并 422；保留方非 ACTIVE 422
        mvc.perform(post("/admin/places/" + dup.getId() + "/merge").param("keepId", String.valueOf(keep.getId())).with(user(ops)).with(csrf()).header("HX-Request", "true"))
                .andExpect(status().isUnprocessableEntity());
        mvc.perform(post("/admin/places/" + dup.getId() + "/delist").with(user(ops)).with(csrf()).header("HX-Request", "true"))
                .andExpect(status().isUnprocessableEntity());
        Place third = place(newUser(), "Third " + UUID.randomUUID());
        mvc.perform(post("/admin/places/" + third.getId() + "/merge").param("keepId", String.valueOf(third.getId())).with(user(ops)).with(csrf()).header("HX-Request", "true"))
                .andExpect(status().isUnprocessableEntity());
        mvc.perform(post("/admin/places/" + third.getId() + "/merge").param("keepId", String.valueOf(dup.getId())).with(user(ops)).with(csrf()).header("HX-Request", "true"))
                .andExpect(status().isUnprocessableEntity());
        assertThat(places.findById(third.getId()).orElseThrow().getStatus()).isEqualTo(PlaceStatus.ACTIVE);
    }
}
