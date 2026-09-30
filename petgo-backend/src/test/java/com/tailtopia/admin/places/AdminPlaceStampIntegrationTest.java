package com.tailtopia.admin.places;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.tailtopia.admin.account.domain.AdminPermissions;
import com.tailtopia.admin.account.domain.AdminRole;
import com.tailtopia.admin.account.service.AdminAccountService;
import com.tailtopia.admin.audit.repository.AdminAuditLogRepository;
import com.tailtopia.admin.audit.service.AuditActions;
import com.tailtopia.admin.places.domain.Place;
import com.tailtopia.admin.places.repository.PlaceRepository;
import com.tailtopia.admin.places.service.AdminPlaceService;
import com.tailtopia.admin.places.service.PlaceMergeService;
import com.tailtopia.admin.places.service.PlaceTokenGenerator;
import com.tailtopia.admin.seed.dto.UploadedImage;
import com.tailtopia.admin.seed.service.AdminSeedImageService;
import com.tailtopia.admin.service.AdminUserDetails;
import com.tailtopia.admin.service.AdminUserDetailsService;
import com.tailtopia.auth.domain.User;
import com.tailtopia.profile.domain.PetProfile;
import com.tailtopia.profile.domain.PetType;
import com.tailtopia.profile.repository.PetProfileRepository;
import com.tailtopia.support.ApiIntegrationTest;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;
import javax.imageio.ImageIO;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

/**
 * V1.3.2 batch-a Story 1.4 · L1（真库 + 真 Thymeleaf）：场所专属章上传 / 替换 / 移除（AC2 / AC3）+ App 显示（AC4.3）。
 * OSS 上传用 MockitoBean 替身（本测试验证的是「先校验再上传、只存 objectKey、审计、不清空」，不是 OSS 本身）。
 */
class AdminPlaceStampIntegrationTest extends ApiIntegrationTest {

    @Autowired
    private PlaceRepository places;
    @Autowired
    private PlaceTokenGenerator tokens;
    @Autowired
    private AdminAuditLogRepository audits;
    @Autowired
    private AdminAccountService accountService;
    @Autowired
    private AdminUserDetailsService userDetailsService;
    @Autowired
    private AdminPlaceService placeService;
    @Autowired
    private PlaceMergeService mergeService;
    @Autowired
    private PetProfileRepository petProfiles;
    @MockitoBean
    private AdminSeedImageService images;

    private AdminUserDetails admin(String... perms) {
        long seq = SEQ.incrementAndGet();
        String email = "ps-" + seq + "@tailtopia.test";
        accountService.createAccount(email, "专属章 " + seq, AdminRole.CUSTOM, List.of(perms), 980000L + seq);
        return userDetailsService.loadByEmail(email, false);
    }

    private Place place(User marker, String name) {
        return places.save(Place.create(tokens.generate(), name, "CAFE", List.of("PETS_ALLOWED_INSIDE"), "desc", "Jakarta",
                "Jl. " + name, new BigDecimal("-6.208763"), new BigDecimal("106.845599"), marker.getId()));
    }

    private static byte[] rgba512() throws Exception {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        ImageIO.write(new BufferedImage(512, 512, BufferedImage.TYPE_INT_ARGB), "png", out);
        return out.toByteArray();
    }

    private static byte[] rgb(int w, int h) throws Exception {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        ImageIO.write(new BufferedImage(w, h, BufferedImage.TYPE_INT_RGB), "png", out);
        return out.toByteArray();
    }

    private void stubUpload() {
        when(images.upload(any(), anyString())).thenAnswer(inv -> new UploadedImage("https://cdn.test/s.png", 512, 512, null,
                "public/" + inv.getArgument(1, String.class) + "/" + UUID.randomUUID() + ".png", 1234L));
    }

    private String upload(AdminUserDetails ops, long id, byte[] bytes, int expectStatus) throws Exception {
        return mvc.perform(multipart("/admin/places/" + id + "/stamp")
                        .file(new MockMultipartFile("file", "stamp.png", "image/png", bytes))
                        .param("lang", "zh_CN").with(user(ops)).with(csrf()).header("HX-Request", "true"))
                .andExpect(status().is(expectStatus)).andReturn().getResponse().getContentAsString();
    }

    private boolean audited(String action, long id) {
        return audits.findAllByOrderByIdAsc().stream()
                .anyMatch(a -> action.equals(a.getActionType()) && String.valueOf(id).equals(a.getTargetId()));
    }

    @Test
    void uploadReplaceRemoveWithAuditAndDrawerCard() throws Exception {
        AdminUserDetails ops = admin(AdminPermissions.PLACE_MANAGE);
        Place p = place(newUser(), "Kopi " + UUID.randomUUID());
        stubUpload();

        // 抽屉：未上传 = 中性「使用默认章」+ 规范说明 + 上传入口
        String drawer = mvc.perform(get("/admin/places/" + p.getId() + "/drawer").param("lang", "zh_CN")
                        .with(user(ops)).header("HX-Request", "true"))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        assertThat(drawer).contains("专属章").contains("使用默认章").contains("512×512").contains("accept=\"image/png\"")
                .contains("data-reset-file"); // 复审：422 后同名文件重选也能再触发上传

        String html = upload(ops, p.getId(), rgba512(), 200);
        assertThat(html).contains("专属章已更新").contains("pl-stamp-preview");
        String first = places.findById(p.getId()).orElseThrow().getStampObjectKey();
        assertThat(first).startsWith("public/place-stamps/" + p.getId() + "/");
        assertThat(audited(AuditActions.PLACE_STAMP_UPLOADED, p.getId())).isTrue();

        upload(ops, p.getId(), rgba512(), 200); // 替换
        assertThat(places.findById(p.getId()).orElseThrow().getStampObjectKey()).isNotEqualTo(first);
        assertThat(audits.findAllByOrderByIdAsc().stream()
                .filter(a -> AuditActions.PLACE_STAMP_UPLOADED.equals(a.getActionType())
                        && String.valueOf(p.getId()).equals(a.getTargetId()))
                .anyMatch(a -> a.getSummary().contains("replaced=true") && !a.getSummary().contains("png"))).isTrue();

        mvc.perform(post("/admin/places/" + p.getId() + "/stamp/remove").param("lang", "zh_CN")
                        .with(user(ops)).with(csrf()).header("HX-Request", "true"))
                .andExpect(status().isOk());
        assertThat(places.findById(p.getId()).orElseThrow().getStampObjectKey()).isNull();
        assertThat(audited(AuditActions.PLACE_STAMP_REMOVED, p.getId())).isTrue();
    }

    @Test
    void invalidFilesAre422AndNeverUploaded() throws Exception {
        AdminUserDetails ops = admin(AdminPermissions.PLACE_MANAGE);
        Place p = place(newUser(), "Kopi " + UUID.randomUUID());

        assertThat(upload(ops, p.getId(), "not a png".getBytes(), 422)).contains("只支持 PNG");
        assertThat(upload(ops, p.getId(), rgb(511, 512), 422)).contains("511×512");
        assertThat(upload(ops, p.getId(), rgb(512, 512), 422)).contains("透明");
        byte[] big = new byte[301 * 1024];
        System.arraycopy(rgba512(), 0, big, 0, 8);
        assertThat(upload(ops, p.getId(), big, 422)).contains("300KB");
        verify(images, never()).upload(any(), anyString());
        assertThat(places.findById(p.getId()).orElseThrow().getStampObjectKey()).isNull();
    }

    @Test
    void noPermissionIs403() throws Exception {
        Place p = place(newUser(), "Kopi " + UUID.randomUUID());
        upload(admin(AdminPermissions.CONTENT_VIEW), p.getId(), rgba512(), 403);
    }

    @Test
    void mergeAndDelistKeepTheStamp() throws Exception {
        AdminUserDetails ops = admin(AdminPermissions.PLACE_MANAGE);
        User marker = newUser();
        Place a = place(marker, "A " + UUID.randomUUID());
        Place b = place(marker, "B " + UUID.randomUUID());
        Place c = place(marker, "C " + UUID.randomUUID());
        stubUpload();
        upload(ops, a.getId(), rgba512(), 200);
        upload(ops, b.getId(), rgba512(), 200);
        upload(ops, c.getId(), rgba512(), 200);
        String aKey = places.findById(a.getId()).orElseThrow().getStampObjectKey();
        String bKey = places.findById(b.getId()).orElseThrow().getStampObjectKey();
        String cKey = places.findById(c.getId()).orElseThrow().getStampObjectKey();

        mergeService.merge(b.getId(), a.getId(), ops.getAdminAccountId());
        placeService.delist(c.getId(), ops.getAdminAccountId());

        assertThat(places.findById(b.getId()).orElseThrow().getStampObjectKey()).isEqualTo(bKey);
        assertThat(places.findById(a.getId()).orElseThrow().getStampObjectKey()).isEqualTo(aKey);
        assertThat(places.findById(c.getId()).orElseThrow().getStampObjectKey()).isEqualTo(cKey);
        // MERGED 场所不出上传入口、上传被拒
        assertThat(upload(ops, b.getId(), rgba512(), 422)).contains("已合并");
    }

    /** AC4.3：换章对已盖出的章立即生效 —— 打卡 → 上传 → 护照返回 URL → 替换 → 新 URL → 移除 → null。 */
    @Test
    void replacingTheStampAppliesToStampsAlreadyCollected() throws Exception {
        AdminUserDetails ops = admin(AdminPermissions.PLACE_MANAGE);
        User u = newUser();
        PetProfile pet = petProfiles.save(PetProfile.create(u.getId(), PetType.CAT, "Momo", null, null, null, null,
                "TOK-" + SEQ.incrementAndGet()));
        Place p = place(u, "Kopi " + UUID.randomUUID());
        mvc.perform(post("/api/v1/places/" + p.getPublicToken() + "/checkins").header("Authorization", userBearer(u.getId()))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"latitude\":-6.208763,\"longitude\":106.845599,\"petIds\":[" + pet.getId() + "]}"))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.stampImageUrl").doesNotExist());
        stubUpload();

        upload(ops, p.getId(), rgba512(), 200);
        String first = places.findById(p.getId()).orElseThrow().getStampObjectKey();
        mvc.perform(get("/api/v1/pet-profiles/me/passport").header("Authorization", userBearer(u.getId())))
                .andExpect(jsonPath("$.stamps[0].stampImageUrl").value(org.hamcrest.Matchers.endsWith(first)));

        upload(ops, p.getId(), rgba512(), 200);
        String second = places.findById(p.getId()).orElseThrow().getStampObjectKey();
        mvc.perform(get("/api/v1/pet-profiles/me/passport").header("Authorization", userBearer(u.getId())))
                .andExpect(jsonPath("$.stamps[0].stampImageUrl").value(org.hamcrest.Matchers.endsWith(second)))
                .andExpect(jsonPath("$.stamps[0].stampImageUrl").value(
                        org.hamcrest.Matchers.not(org.hamcrest.Matchers.containsString("x-oss-process"))));

        placeService.removeStamp(p.getId(), ops.getAdminAccountId());
        mvc.perform(get("/api/v1/pet-profiles/me/passport").header("Authorization", userBearer(u.getId())))
                .andExpect(jsonPath("$.stamps[0].stampImageUrl").doesNotExist());
    }
}
