package com.tailtopia.admin.growth.web;

import com.tailtopia.admin.account.domain.AdminPermissions;
import com.tailtopia.admin.growth.service.AdminGrowthBatchService;
import com.tailtopia.admin.growth.service.AdminGrowthBatchService.Preview;
import com.tailtopia.admin.growth.service.AdminGrowthBatchService.Result;
import com.tailtopia.admin.growth.service.GrowthBatchParser;
import com.tailtopia.admin.service.AdminUserDetails;
import com.tailtopia.admin.shared.web.AdminConfirmModal;
import com.tailtopia.admin.shared.web.AdminFragmentResponses;
import com.tailtopia.admin.shared.web.AdminHxEvents;
import com.tailtopia.admin.shared.web.HxRequest;
import com.tailtopia.shared.error.AppException;
import com.tailtopia.shared.i18n.Messages;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.multipart.MultipartFile;

/**
 * 「成长体系」页的批量发放抽屉（V1.4.0 ① Story 4.3 · UI H1 ~ H5）。抽屉体各步互换：{@code HX-Retarget #growthbatch-drawer-body}
 * + innerHTML；确认弹层回 {@code #confirm-host}（growth.html 已有）。
 *
 * <p>🛡 全部端点门 = {@code growth.user_adjust}（与 Story 4.2 单个调整同一常量），无码 403。每一步都按提交值重新解析、重新归类，
 * 不信任上一步的页面。
 */
@Controller
public class AdminGrowthBatchController {

    private static final Logger log = LoggerFactory.getLogger(AdminGrowthBatchController.class);

    /**
     * 与 {@link AdminGrowthUserController#GROWTH_ADJUST_AUTH} 同值（L0 测试钉住相等）；在本类再声明一次是因为
     * {@code check-admin-permission-consistency.sh} 只解析同文件内的常量。
     */
    static final String GROWTH_ADJUST_AUTH = "hasRole('SUPER_ADMIN') or hasAuthority('"
            + AdminPermissions.GROWTH_USER_ADJUST + "')";

    static final String BASE = "/admin/growth/batch";
    static final String BODY = "#growthbatch-drawer-body";
    static final String TPL = "admin/fragments/drawer-growth-batch";

    /** 422 文案码 → 第 ① 步字段（字段下红字）；其余落表单顶部错误条。 */
    static final Map<String, String> FIELD_OF = Map.of(
            "admin.err.growth.batch.campaignRequired", "campaign",
            "admin.err.growth.batch.campaignTooLong", "campaign",
            "admin.err.growth.batch.expInvalid", "expPerUser",
            "admin.err.growth.batch.idsRequired", "userIds",
            "admin.err.growth.batch.idsTooMany", "userIds",
            "admin.err.growth.batch.fileTooLarge", "userIds");

    private final AdminGrowthBatchService service;
    private final Messages msg;

    public AdminGrowthBatchController(AdminGrowthBatchService service, Messages msg) {
        this.service = service;
        this.msg = msg;
    }

    /** ① 填写（空表单）。非 htmx → 回成长体系页。 */
    @GetMapping(BASE + "/drawer")
    @PreAuthorize(GROWTH_ADJUST_AUTH)
    public String drawer(HxRequest hx, Model model) {
        if (!hx.isHtmx()) {
            return "redirect:/admin/growth";
        }
        fillModel(model, null, null, null, Map.of(), null);
        return TPL + " :: fill";
    }

    /** CSV 模板：表头 user_id + 一行示例。 */
    @GetMapping(BASE + "/template.csv")
    @PreAuthorize(GROWTH_ADJUST_AUTH)
    public ResponseEntity<byte[]> template() {
        return ResponseEntity.ok()
                .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=\"growth-batch-template.csv\"")
                .contentType(MediaType.parseMediaType("text/csv; charset=UTF-8"))
                .body("user_id\n10001\n".getBytes(StandardCharsets.UTF_8));
    }

    /** 「← 上一步」：回第 ① 步并回填（文件不保留，解析出的 ID 回填进 textarea）。 */
    @PostMapping(BASE + "/form")
    @PreAuthorize(GROWTH_ADJUST_AUTH)
    public String back(@RequestParam(value = "campaign", required = false) String campaign,
            @RequestParam(value = "expPerUser", required = false) String expPerUser,
            @RequestParam(value = "userIds", required = false) String userIds, Model model,
            HttpServletResponse response) {
        retargetBody(response);
        fillModel(model, campaign, expPerUser, userIds == null ? null : userIds.replace(",", "\n"), Map.of(), null);
        return TPL + " :: fill";
    }

    /** ② 预览（multipart：粘贴 + 可选 CSV，取并集）。校验失败 422 回第 ① 步、字段下红字。 */
    @PostMapping(BASE + "/preview")
    @PreAuthorize(GROWTH_ADJUST_AUTH)
    public String preview(@RequestParam(value = "campaign", required = false) String campaign,
            @RequestParam(value = "expPerUser", required = false) String expPerUser,
            @RequestParam(value = "userIds", required = false) String userIds,
            @RequestParam(value = "file", required = false) MultipartFile file, Model model,
            HttpServletResponse response) {
        retargetBody(response);
        try {
            Preview p = service.preview(campaign, expPerUser, userIds, readCsv(file));
            model.addAttribute("pv", p);
            return TPL + " :: preview";
        } catch (AppException e) {
            response.setStatus(422);
            String field = FIELD_OF.get(e.getMessageCode());
            fillModel(model, campaign, expPerUser, userIds, field == null ? Map.of() : Map.of(field, msg.resolve(e)),
                    field == null ? msg.resolve(e) : null);
            return TPL + " :: fill";
        }
    }

    /** ③ 确认弹层：重新解析 + 归类；有效 0 → 422（回抽屉错误槽 #growthbatch-err）。 */
    @PostMapping(BASE + "/confirm")
    @PreAuthorize(GROWTH_ADJUST_AUTH)
    public String confirm(@RequestParam(value = "batchNo", required = false) String batchNo,
            @RequestParam(value = "campaign", required = false) String campaign,
            @RequestParam(value = "expPerUser", required = false) String expPerUser,
            @RequestParam(value = "userIds", required = false) String userIds, HxRequest hx, Model model,
            HttpServletResponse response) {
        if (!hx.isHtmx()) {
            return "redirect:/admin/growth";
        }
        Preview p = service.prepareConfirm(batchNo, campaign, expPerUser, userIds);
        model.addAttribute("cm", confirmModal(p));
        response.setHeader(AdminFragmentResponses.HEADER_RETARGET, "#confirm-host");
        response.setHeader(AdminFragmentResponses.HEADER_RESWAP, "innerHTML");
        return "admin/fragments/confirm-modal :: modal";
    }

    /**
     * ④ 执行：成功 = 收弹层 + 抽屉体换结果页 + toast；业务 422 留在弹层内；未预期异常 → 抽屉体换失败页（H5，422 以便 htmx 换入；
     * 不伪装成功），可用同一批次号重试。
     */
    @PostMapping(BASE)
    @PreAuthorize(GROWTH_ADJUST_AUTH)
    public String execute(@AuthenticationPrincipal AdminUserDetails admin,
            @RequestParam(value = "batchNo", required = false) String batchNo,
            @RequestParam(value = "campaign", required = false) String campaign,
            @RequestParam(value = "expPerUser", required = false) String expPerUser,
            @RequestParam(value = "userIds", required = false) String userIds,
            @RequestParam(value = "validCount", required = false) String validCount, HxRequest hx, Model model,
            HttpServletResponse response) {
        if (!hx.isHtmx()) {
            return "redirect:/admin/growth";
        }
        Result r;
        try {
            r = service.execute(batchNo, campaign, expPerUser, userIds, admin.getAdminAccountId());
        } catch (AppException e) {
            throw e; // 422 / 404 由 AdminBusinessExceptionAdvice 回弹层内 #confirm-modal-err
        } catch (RuntimeException e) {
            log.error("growth batch execute failed: batchNo={} error={}", batchNo, e.getClass().getSimpleName());
            response.setStatus(422);
            response.setHeader("HX-Trigger-After-Swap", AdminHxEvents.CONFIRM_CLOSE);
            retargetBody(response);
            model.addAttribute("fb", new Failed(batchNo, campaign, expPerUser, userIds, validCount));
            return TPL + " :: failed";
        }
        response.setHeader("HX-Trigger-After-Swap", AdminHxEvents.CONFIRM_CLOSE);
        retargetBody(response);
        model.addAttribute("rs", r);
        model.addAttribute("retry", new Failed(r.batchNo(), campaign, expPerUser, userIds, validCount));
        if (r.granted() > 0) {
            model.addAttribute("toast", msg.get("admin.flash.growth.batchGranted", r.totalExp(), r.granted()));
        }
        return TPL + " :: result";
    }

    /** 失败页 / 重试表单的回填值（原样回传，服务端下次照旧重新解析）。 */
    public record Failed(String batchNo, String campaign, String expPerUser, String userIds, String validCount) {
    }

    private AdminConfirmModal confirmModal(Preview p) {
        Map<String, String> hidden = new LinkedHashMap<>();
        hidden.put("batchNo", p.batchNo());
        hidden.put("campaign", p.campaign());
        hidden.put("expPerUser", String.valueOf(p.expPerUser()));
        hidden.put("userIds", p.normalizedIds());
        hidden.put("validCount", String.valueOf(p.valid()));
        List<AdminConfirmModal.Fact> facts = List.of(
                new AdminConfirmModal.Fact(msg.get("admin.v140.growth.batch.confirm.campaign"), p.campaign()),
                new AdminConfirmModal.Fact(msg.get("admin.v140.growth.batch.confirm.batchNo"), p.batchNo()));
        // 正文「确认给 N 位用户各发 X EXP（合计 T EXP）？」放黄色提示条（同 AB-31 I8 大范围发送的形态）
        return new AdminConfirmModal(msg.get("admin.v140.growth.batch.confirm.title"),
                msg.get("admin.v140.growth.batch.confirm.body", p.valid(), p.expPerUser(), p.total()), List.of(),
                msg.get("admin.v140.growth.batch.confirm.note"), null, null, null, BASE, hidden,
                msg.get("admin.v140.growth.batch.confirmBtn"), false, true, facts);
    }

    private void fillModel(Model model, String campaign, String expPerUser, String userIds,
            Map<String, String> fieldErrors, String formError) {
        model.addAttribute("bf", new Failed(null, campaign, expPerUser, userIds, null));
        model.addAttribute("fieldErrors", fieldErrors);
        model.addAttribute("formError", formError);
        model.addAttribute("maxIds", GrowthBatchParser.MAX_IDS);
    }

    private static void retargetBody(HttpServletResponse response) {
        response.setHeader(AdminFragmentResponses.HEADER_RETARGET, BODY);
        response.setHeader(AdminFragmentResponses.HEADER_RESWAP, "innerHTML");
    }

    /** CSV ≤ 1 MB，按 UTF-8 读；空文件 = 没传。 */
    private static String readCsv(MultipartFile file) {
        if (file == null || file.isEmpty()) {
            return null;
        }
        if (file.getSize() > GrowthBatchParser.MAX_CSV_BYTES) {
            throw AppException.validation("CSV 文件不能超过 1 MB").code("admin.err.growth.batch.fileTooLarge");
        }
        try {
            return new String(file.getBytes(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw AppException.validation("请填写或上传用户 ID").code("admin.err.growth.batch.idsRequired");
        }
    }
}
