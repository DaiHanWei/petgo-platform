package com.tailtopia.admin.seed.web;

import com.tailtopia.admin.seed.domain.SeedBatch;
import com.tailtopia.admin.seed.service.SeedBatchAssetService;
import com.tailtopia.admin.seed.service.SeedBatchService;
import com.tailtopia.admin.service.AdminUserDetails;
import com.tailtopia.shared.error.AppException;
import java.util.Map;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseBody;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;

/**
 * 批量内容工作台 —— 素材墙那一步（V1.1.6 Story 13.2 · AB-3K Step 1）。
 *
 * <p>本 story 只交付 Step 1：开批次、拖素材、看缩略图墙与配额。
 * 内容录入（13-3）、校验预览（13-4）、定时发布（13-5）都会长在这同一个工作台上。
 *
 * <p>⚠️ <b>名字里的 Workspace 不是修饰</b>：{@code admin.virtual.web.AdminSeedBatchController}
 * 已经存在（Story 9.8 的"选虚拟账号 + 多行文本立即发"那条老路径），
 * 同名会直接撞 Spring 的 bean 名。两者也确实是两回事 ——
 * 那条是"贴进去就发"，本工作台是"存下来、逐行校验、按需排期"。
 */
@Controller
public class AdminSeedBatchWorkspaceController {

    private static final String AUTH =
            "hasRole('SUPER_ADMIN') or hasAuthority('virtual_account.manage')";

    private final SeedBatchService batches;
    private final SeedBatchAssetService assets;

    public AdminSeedBatchWorkspaceController(SeedBatchService batches,
            SeedBatchAssetService assets) {
        this.batches = batches;
        this.assets = assets;
    }

    /** 批次列表 —— 🛡 按各行状态**聚合**展示（13-1 AC2），批次自己没有状态。 */
    @GetMapping("/admin/seed-batches")
    @PreAuthorize(AUTH)
    public String list(Model model) {
        model.addAttribute("active", "seed");
        model.addAttribute("batches", batches.recentBatches());
        return "admin/seed-batches";
    }

    @PostMapping("/admin/seed-batches")
    @PreAuthorize(AUTH)
    public String open(@AuthenticationPrincipal AdminUserDetails admin,
            @RequestParam(defaultValue = "ONLINE_PASTE") SeedBatch.Source source,
            RedirectAttributes flash) {
        SeedBatch b = batches.openBatch(source, admin.getAdminAccountId());
        flash.addFlashAttribute("notice", "已新建批次 #" + b.getId());
        return "redirect:/admin/seed-batches/" + b.getId();
    }

    /** 单个批次的工作台。 */
    @GetMapping("/admin/seed-batches/{batchId}")
    @PreAuthorize(AUTH)
    public String workspace(@PathVariable long batchId, Model model) {
        model.addAttribute("active", "seed");
        model.addAttribute("batchId", batchId);
        model.addAttribute("rows", batches.rowsOf(batchId));
        addWall(model, batchId);
        return "admin/seed-batch-workspace";
    }

    /**
     * 上传一张素材。
     *
     * <p>🔴 <b>一次一张</b>（同 12-2 的理由）：批量里有一张被拒（重名 / HEIC / 超限），
     * 整批一起失败会让运营重传全部，回"部分成功"的界面复杂度又远超收益。
     * 一张一个请求 ⇒ 被拒那张单独标红、其余照常。
     *
     * <p>错误一律 400 + {@code {"error": ...}}：重名与超限都是**预期内的用户输入**，不是故障。
     */
    @PostMapping("/admin/seed-batches/{batchId}/assets")
    @PreAuthorize(AUTH)
    @ResponseBody
    public ResponseEntity<?> uploadAsset(@PathVariable long batchId,
            @RequestParam("file") MultipartFile file) {
        try {
            var saved = assets.upload(batchId, file);
            var used = assets.usage(batchId);
            return ResponseEntity.ok(Map.of(
                    "id", saved.getId(),
                    "fileName", saved.getFileName(),
                    "url", saved.getUrl(),
                    "w", saved.getWidth(),
                    "h", saved.getHeight(),
                    "sizeBytes", saved.getSizeBytes(),
                    // 每次都回权威用量：页面上那个计数器是体验，服务端这个才是真相。
                    "usedCount", used.count(),
                    "usedBytes", used.bytes()));
        } catch (AppException e) {
            return ResponseEntity.badRequest().body(Map.of("error", e.getMessage()));
        } catch (Exception e) {
            return ResponseEntity.badRequest().body(Map.of("error", "素材上传失败，请重试"));
        }
    }

    /** 缩略图墙（HTMX 片段）。上传完刷这一块，不整页刷新。 */
    @GetMapping("/admin/seed-batches/{batchId}/assets")
    @PreAuthorize(AUTH)
    public String wall(@PathVariable long batchId, Model model) {
        addWall(model, batchId);
        return "admin/fragments/seed-asset-wall :: wall";
    }

    private void addWall(Model model, long batchId) {
        model.addAttribute("assets", assets.wall(batchId));
        model.addAttribute("usage", assets.usage(batchId));
        model.addAttribute("wallBatchId", batchId);
    }
}
