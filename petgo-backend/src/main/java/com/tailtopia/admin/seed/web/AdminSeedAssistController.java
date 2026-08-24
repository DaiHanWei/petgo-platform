package com.tailtopia.admin.seed.web;

import com.tailtopia.admin.seed.dto.PetOption;
import com.tailtopia.admin.seed.dto.UploadedImage;
import com.tailtopia.admin.seed.service.AdminSeedImageService;
import com.tailtopia.profile.repository.PetProfileRepository;
import com.tailtopia.shared.error.AppException;
import java.util.List;
import java.util.Map;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseBody;
import org.springframework.web.multipart.MultipartFile;

/**
 * 单条发布页的两个辅助端点（V1.1.6 Story 12.2 · AB-3J）。
 *
 * <ul>
 *   <li><b>图片上传</b>（AC2/AC3）：返回 JSON —— 页面按顺序拼进隐藏字段，顺序就是首图顺序。</li>
 *   <li><b>宠物下拉</b>（AC4）：返回 HTML 片段，由 HTMX 在切换发布账号时换掉。</li>
 * </ul>
 *
 * <p>🛡 门与单条发布页一致（{@code virtual_account.manage}）：这两个端点都能间接看到
 * "某账号名下有哪些宠物"以及"能往公开桶写东西"，不该比它所服务的那一页更松。
 */
@Controller
public class AdminSeedAssistController {

    private static final String AUTH =
            "hasRole('SUPER_ADMIN') or hasAuthority('virtual_account.manage')";

    private final AdminSeedImageService images;
    private final PetProfileRepository pets;

    public AdminSeedAssistController(AdminSeedImageService images, PetProfileRepository pets) {
        this.images = images;
        this.pets = pets;
    }

    /**
     * 上传一张内容图。
     *
     * <p>🔴 <b>一次一张，而不是一次一批</b>：批量上传里有一张被拒（HEIC / 超过 10MB），
     * 整批要么一起失败、要么得回一个"部分成功"的结果 —— 前者让运营重传全部，
     * 后者的界面复杂度远超收益。一张一个请求，页面自己并发发几个，
     * 失败的那张单独标红、其余照常，这才是运营要的行为。
     *
     * <p>错误一律回 400 + {@code {"error": "..."}}：页面把它贴在那张缩略图下面。
     * ⚠️ <b>不要回 500</b> —— HEIC 与超大图是**预期内**的用户输入，不是服务故障。
     */
    @PostMapping("/admin/seed-post/images")
    @PreAuthorize(AUTH)
    @ResponseBody
    public ResponseEntity<?> upload(@RequestParam("file") MultipartFile file) {
        try {
            UploadedImage r = images.upload(file, "seed-post");
            return ResponseEntity.ok(r);
        } catch (AppException e) {
            return ResponseEntity.badRequest().body(Map.of("error", e.getMessage()));
        } catch (Exception e) {
            // 对象存储未配置 / 凭证异常等 —— 优雅回显，不抛 500 到页面上。
            return ResponseEntity.badRequest().body(Map.of("error", "图片上传失败，请重试"));
        }
    }

    /**
     * 某发布账号名下的宠物档案（HTMX 片段）。
     *
     * <p>⚠️ <b>V1 是「单账号单宠物」</b>（`uq_pet_profiles_owner_id` 唯一约束），
     * 所以这个下拉实际只会有 0 或 1 项。仍然做成下拉而不是"显示那一只"，
     * 是因为 14-1 的「关联物种」要挂在它之后，而且将来放开多宠物时这里不用重做。
     *
     * <p>🔴 <b>虚拟账号必然是 0 项</b> —— 它没有宠物档案。这正是成长日历
     * （{@code GROWTH_MOMENT}）此前发不出来的原因：那类内容必须绑档案。
     * 引入运营真实账号（有档案）后这条路才真正通。
     */
    @GetMapping("/admin/seed-post/pets")
    @PreAuthorize(AUTH)
    public String petOptions(@RequestParam(required = false) Long authorUserId, Model model) {
        List<PetOption> options = authorUserId == null ? List.of()
                : pets.findByOwnerId(authorUserId)
                        .map(p -> List.of(new PetOption(p.getId(), p.getName(),
                                p.getPetType() == null ? "—" : p.getPetType().name())))
                        .orElseGet(List::of);
        model.addAttribute("petOptions", options);
        return "admin/fragments/seed-pet-select :: options";
    }
}
