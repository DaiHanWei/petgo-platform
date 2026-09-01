package com.tailtopia.admin.seed.web;

import com.tailtopia.admin.seed.domain.SeedBatch;
import com.tailtopia.admin.seed.service.SeedBatchAssetService;
import com.tailtopia.admin.seed.service.SeedBatchService;
import com.tailtopia.admin.service.AdminUserDetails;
import com.tailtopia.content.domain.ContentType;
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

    /** 运营填的墙上时间按这个时区解释（与顶置管理、Excel 导入同口径）。 */
    private static final java.time.ZoneId WIB = java.time.ZoneId.of("Asia/Jakarta");

    private final SeedBatchService batches;
    private final SeedBatchAssetService assets;
    private final com.tailtopia.admin.seed.service.SeedBatchEntryService entry;
    private final com.tailtopia.admin.seed.service.SeedBatchExcelService excel;
    private final com.tailtopia.admin.virtual.service.AdminPublishIdentityService identities;
    private final com.tailtopia.admin.seed.service.SeedBatchPublishService publishing;

    /** 批次列表页底部那段排期用（bug 20260826：上传与看情况同一页）。 */
    private final com.tailtopia.admin.seed.repository.SeedBatchRowRepository scheduleRows;
    /** 确认发布的结果提示按当前语言输出（bug 20260901-473，后台三语）。 */
    private final com.tailtopia.shared.i18n.Messages i18n;

    public AdminSeedBatchWorkspaceController(SeedBatchService batches,
            SeedBatchAssetService assets,
            com.tailtopia.admin.seed.service.SeedBatchEntryService entry,
            com.tailtopia.admin.seed.service.SeedBatchExcelService excel,
            com.tailtopia.admin.virtual.service.AdminPublishIdentityService identities,
            com.tailtopia.admin.seed.service.SeedBatchPublishService publishing,
            com.tailtopia.admin.seed.repository.SeedBatchRowRepository scheduleRows,
            com.tailtopia.shared.i18n.Messages i18n) {
        this.batches = batches;
        this.assets = assets;
        this.entry = entry;
        this.excel = excel;
        this.identities = identities;
        this.publishing = publishing;
        this.scheduleRows = scheduleRows;
        this.i18n = i18n;
    }

    /** 批次列表 —— 🛡 按各行状态**聚合**展示（13-1 AC2），批次自己没有状态。 */
    @GetMapping("/admin/seed-batches")
    @PreAuthorize(AUTH)
    public String list(Model model) {
        model.addAttribute("active", "seed");
        model.addAttribute("batches", batches.recentBatches());
        // 排期段与「排期管理」页共用同一个模板片段，故模型键也必须一致（rows / authorId）。
        // bug 20260826：产品要求上传与看情况同一页 —— 运营发完一批不该再换页才知道何时发。
        // ⚠️ 这里刻意**不带 authorId 过滤**：本页的排期是「这批发出去之后的整体情况」，
        //    按账号筛的诉求在排期页那边（片段里的筛选表单仍指向那个页面）。
        model.addAttribute("authorId", null);
        model.addAttribute("rows", scheduleRows.findByStatusInOrderByScheduledAtAsc(
                com.tailtopia.admin.seed.web.AdminContentScheduleController.LISTED));
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
        var batchRows = batches.rowsOf(batchId);
        model.addAttribute("rows", batchRows);
        model.addAttribute("batch", entry.findBatch(batchId).orElse(null));
        // bug 20260901-468：行内「图片」输入框的回显值。行存的是 URL、输入框填的是文件名，
        // 这里反查一次拼回逗号串 —— 不回显的话，再点一次保存会把已绑定的图片当成"清空"抹掉。
        java.util.Map<String, String> urlToName = new java.util.HashMap<>();
        for (var a : assets.wall(batchId)) {
            urlToName.put(a.getUrl(), a.getFileName());
        }
        java.util.Map<Long, String> rowAssetNames = new java.util.HashMap<>();
        for (var r : batchRows) {
            if (r.getImageUrls() == null || r.getImageUrls().isEmpty()) {
                continue;
            }
            rowAssetNames.put(r.getId(), r.getImageUrls().stream()
                    .map(u -> urlToName.getOrDefault(u, u)).collect(java.util.stream.Collectors.joining(",")));
        }
        model.addAttribute("rowAssetNames", rowAssetNames);
        // 🔴 批次级设置的三个下拉数据源。**全页只有这一处** —— 此前在线录入与
        //    Excel 导入各带一个一模一样的账号下拉，同一页面出现两次。
        model.addAttribute("publishIdentities", identities.selectableIdentities());
        model.addAttribute("batchTypes",
                com.tailtopia.admin.seed.service.SeedBatchExcelService.BATCH_TYPES);
        model.addAttribute("speciesOptions",
                com.tailtopia.admin.seed.service.SeedBatchExcelService.SPECIES_OPTIONS);
        addWall(model, batchId);
        return "admin/seed-batch-workspace";
    }

    /**
     * 单张素材删除（bug 20260901-474）：标记废弃，OSS 对象不物理删（F21）。
     * 被待发布行引用时拒绝并指出行号（校验在服务层，权威）。
     */
    @PostMapping("/admin/seed-batches/{batchId}/assets/{assetId}/delete")
    @PreAuthorize(AUTH)
    public String removeAsset(@PathVariable long batchId, @PathVariable long assetId,
            RedirectAttributes flash) {
        try {
            assets.remove(batchId, assetId);
            flash.addFlashAttribute("notice", i18n.get("admin.batch.asset.removed"));
        } catch (AppException e) {
            flash.addFlashAttribute("error", e.getMessage());
        }
        return "redirect:/admin/seed-batches/" + batchId;
    }

    /** 页头那一处批次级设置（AC1）。 */
    @PostMapping("/admin/seed-batches/{batchId}/settings")
    @PreAuthorize(AUTH)
    public String saveSettings(@PathVariable long batchId,
            @RequestParam(required = false) Long defaultAuthorUserId,
            @RequestParam(required = false) ContentType defaultContentType,
            @RequestParam(required = false) String defaultScheduledAt,
            RedirectAttributes flash) {
        try {
            entry.saveDefaults(batchId, defaultAuthorUserId, defaultContentType,
                    wallClockToUtc(defaultScheduledAt));
            flash.addFlashAttribute("notice", "已保存批次设置");
        } catch (AppException e) {
            flash.addFlashAttribute("error", e.getMessage());
        }
        return "redirect:/admin/seed-batches/" + batchId;
    }

    /**
     * 粘贴多行 → 逐行生成草稿（AC2/AC3）。
     *
     * <p>🔴 **按一行一条拆分**。界面上那句提示不可省略 —— 粘入带段落的长正文会被拆成残句，
     * 而且**不会有任何报错**（每一段都是合法正文）。
     */
    @PostMapping("/admin/seed-batches/{batchId}/rows/paste")
    @PreAuthorize(AUTH)
    public String paste(@PathVariable long batchId, @RequestParam String lines,
            RedirectAttributes flash) {
        try {
            int n = entry.pasteLines(batchId, lines);
            flash.addFlashAttribute("notice", "已生成 " + n + " 个待编辑行");
        } catch (AppException e) {
            flash.addFlashAttribute("error", e.getMessage());
        }
        return "redirect:/admin/seed-batches/" + batchId;
    }

    @PostMapping("/admin/seed-batches/{batchId}/rows")
    @PreAuthorize(AUTH)
    public String addRow(@PathVariable long batchId, RedirectAttributes flash) {
        entry.addBlankRow(batchId);
        return "redirect:/admin/seed-batches/" + batchId;
    }

    /** 逐行编辑（行卡片上的保存）。空值一律表示"继承默认"。 */
    @PostMapping("/admin/seed-batches/{batchId}/rows/{rowId}")
    @PreAuthorize(AUTH)
    public String editRow(@PathVariable long batchId, @PathVariable long rowId,
            @RequestParam(required = false) String body,
            @RequestParam(required = false) String assetFileNames,
            @RequestParam(required = false) Long authorUserId,
            @RequestParam(required = false) ContentType contentType,
            @RequestParam(required = false) String species,
            @RequestParam(required = false) String scheduledAt,
            RedirectAttributes flash) {
        try {
            entry.editRow(rowId, body, splitNames(assetFileNames), authorUserId, contentType,
                    species, wallClockToUtc(scheduledAt));
            flash.addFlashAttribute("notice", "已保存第 " + rowId + " 行");
        } catch (AppException e) {
            flash.addFlashAttribute("error", e.getMessage());
        }
        return "redirect:/admin/seed-batches/" + batchId;
    }

    @PostMapping("/admin/seed-batches/{batchId}/rows/{rowId}/delete")
    @PreAuthorize(AUTH)
    public String deleteRow(@PathVariable long batchId, @PathVariable long rowId) {
        entry.deleteRow(rowId);
        return "redirect:/admin/seed-batches/" + batchId;
    }

    /** 带下拉数据校验的 Excel 模板（AC4）。 */
    @GetMapping("/admin/seed-batches/{batchId}/template")
    @PreAuthorize(AUTH)
    @ResponseBody
    public ResponseEntity<byte[]> template(@PathVariable long batchId) {
        byte[] bytes = excel.template(identities.selectableIdentities());
        return ResponseEntity.ok()
                .header(org.springframework.http.HttpHeaders.CONTENT_DISPOSITION,
                        "attachment; filename=seed-batch-template.xlsx")
                .contentType(org.springframework.http.MediaType.parseMediaType(
                        "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet"))
                .body(bytes);
    }

    /** Excel 导入（AC4）。🛡 与在线录入**共用同一套字段继承规则**。 */
    @PostMapping("/admin/seed-batches/{batchId}/import")
    @PreAuthorize(AUTH)
    public String importExcel(@PathVariable long batchId, @RequestParam MultipartFile file,
            RedirectAttributes flash) {
        try {
            var raws = excel.parse(file);
            int n = entry.appendRows(batchId, raws).size();
            flash.addFlashAttribute("notice", "已导入 " + n + " 行");
        } catch (AppException e) {
            flash.addFlashAttribute("error", e.getMessage());
        }
        return "redirect:/admin/seed-batches/" + batchId;
    }

    /**
     * 运营填的墙上时间 → UTC。
     *
     * <p>⚠️ 面向印尼市场，运营心里那个"9 月 1 日早上 8 点"是 **WIB**。
     * 按服务器时区解释会整体偏 7 小时，而这种偏差在测试环境（也在 UTC）里看不出来。
     */
    private static java.time.Instant wallClockToUtc(String raw) {
        if (raw == null || raw.isBlank()) {
            return null;
        }
        java.time.Instant at;
        try {
            at = java.time.LocalDateTime.parse(raw.trim().replace(' ', 'T'))
                    .atZone(WIB).toInstant();
        } catch (Exception e) {
            throw AppException.validation("时间格式应为 2026-09-01T08:30");
        }
        // 🔴 V1.1.6 Story 13.5 · AC1：**不可早于当前时刻**。
        //    排一个已经过去的时间，下一轮扫描就会立刻发出去 —— 而运营的本意多半是
        //    "排到某个更晚的时候"，手滑填成过去的日期就成了立即发布，且不可撤回。
        if (!at.isAfter(java.time.Instant.now())) {
            throw AppException.validation("计划发布时间不能早于当前时刻（印尼时间 WIB）");
        }
        return at;
    }

    private static java.util.List<String> splitNames(String raw) {
        if (raw == null || raw.isBlank()) {
            return java.util.List.of();
        }
        java.util.List<String> out = new java.util.ArrayList<>();
        for (String p : raw.split(",")) {
            String t = p.trim();
            if (!t.isEmpty()) {
                out.add(t);
            }
        }
        return out;
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

    /**
     * 校验预览（AC1）。
     *
     * <p>🔴 <b>这一页是本 story 的全部意义</b>：此前提交即上线，
     * 50 行错 3 行就是 3 条线上真帖，只能逐条去找、逐条下架。
     */
    @GetMapping("/admin/seed-batches/{batchId}/preview")
    @PreAuthorize(AUTH)
    public String preview(@PathVariable long batchId, Model model) {
        model.addAttribute("active", "seed");
        model.addAttribute("batchId", batchId);
        var checks = publishing.preview(batchId);
        model.addAttribute("checks", checks);
        model.addAttribute("passCount", checks.stream().filter(c -> c.passes()).count());
        model.addAttribute("failCount", checks.stream().filter(c -> !c.passes()).count());
        model.addAttribute("dupCount", checks.stream().filter(c -> c.warns()).count());
        return "admin/seed-batch-preview";
    }

    /** 确认发布（AC2）。🛡 只发通过的行；失败行留草稿可改后重提。 */
    @PostMapping("/admin/seed-batches/{batchId}/confirm")
    @PreAuthorize(AUTH)
    public String confirm(@AuthenticationPrincipal AdminUserDetails admin,
            @PathVariable long batchId,
            @RequestParam(defaultValue = "false") boolean includeDuplicates,
            RedirectAttributes flash) {
        try {
            var out = publishing.confirm(batchId, admin.getAdminAccountId(), includeDuplicates);
            // bug 20260901-473：改经 i18n 组装（后台三语，硬编码中文会原样怼给印尼运营），
            // 且**每个桶都必须出声** —— 少说一个桶，运营就会觉得有一行凭空消失了。
            StringBuilder msg = new StringBuilder(i18n.get("admin.batch.confirm.published",
                    out.published()));
            if (out.scheduled() > 0) {
                msg.append(i18n.get("admin.batch.confirm.scheduled", out.scheduled()));
            }
            if (out.alreadyDone() > 0) {
                // 此前已发布/已排期的行（比如预览刷两遍再点确认）—— 不说这一句，
                // 汇总看起来就像「表格里的 Pass 行凭空消失」（473 的现场正是如此）。
                msg.append(i18n.get("admin.batch.confirm.alreadyDone", out.alreadyDone()));
            }
            if (out.skippedByError() > 0) {
                // 🛡 措辞写明"留在草稿里可改后重提"—— 只说"跳过 N 条"运营不知道那几条去哪了。
                msg.append(i18n.get("admin.batch.confirm.skippedError", out.skippedByError()));
            }
            if (out.skippedByDuplicate() > 0) {
                msg.append(i18n.get("admin.batch.confirm.skippedDuplicate", out.skippedByDuplicate()));
            }
            if (out.failed() > 0) {
                msg.append(i18n.get("admin.batch.confirm.failed", out.failed()));
            }
            flash.addFlashAttribute("notice", msg.toString());
        } catch (AppException e) {
            flash.addFlashAttribute("error", e.getMessage());
        }
        return "redirect:/admin/seed-batches/" + batchId + "/preview";
    }

    private void addWall(Model model, long batchId) {
        model.addAttribute("assets", assets.wall(batchId));
        model.addAttribute("usage", assets.usage(batchId));
        model.addAttribute("wallBatchId", batchId);
        // bug 20260901-467：内容重复标记（标记提示但放行）。上传后墙经此路径整体刷新，
        // 所以标记在拖完图的下一刻就可见。
        model.addAttribute("assetDupNotes", assets.duplicateNotes(batchId));
    }
}
