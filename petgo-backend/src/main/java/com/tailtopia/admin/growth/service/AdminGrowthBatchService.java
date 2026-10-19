package com.tailtopia.admin.growth.service;

import com.tailtopia.admin.account.repository.AdminAccountRepository;
import com.tailtopia.admin.audit.service.AdminAuditService;
import com.tailtopia.admin.audit.service.AuditActions;
import com.tailtopia.growth.service.AdminAdjustCommand;
import com.tailtopia.growth.service.GrowthLedgerService;
import com.tailtopia.growth.service.GrowthLedgerService.Eligibility;
import com.tailtopia.shared.error.AppException;
import com.tailtopia.shared.schedule.ScheduleWindow;
import java.security.SecureRandom;
import java.time.Clock;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * 批量发放活动 EXP（V1.4.0 ① Story 4.3 · AB-33「运营活动发放 · 批量」· UI H1 ~ H5）。
 *
 * <p>三步：填写 → 预览（解析 + 一次批量归类 + 生成批次号）→ 确认 / 执行（<b>每一步都按隐藏字段重新解析、重新归类</b>，不信任上一步）。
 * 照种子批量「校验未过的行不阻塞整批」：无效 / 虚拟号 / 官方号只计数不发；执行<b>逐人独立事务</b>（REQUIRES_NEW），单人失败只计入
 * {@code failed}，其余照发（整批一个事务会让一人失败回滚全部，见 {@code SeedBatchPublishService} 的事故注释）。
 * 写成长值唯一入口 {@link GrowthLedgerService#adjust}，幂等键 {@code BATCH:{batchNo}:{u}} —— 同一批次重提不重复发。
 * 审计在循环之后独立事务写一条（纯重放不写）。
 */
@Service
public class AdminGrowthBatchService {

    private static final Logger log = LoggerFactory.getLogger(AdminGrowthBatchService.class);

    /** 预览问题清单最多显示的行数。 */
    public static final int MAX_PROBLEM_ROWS = 200;

    /** AD-17：{@code GB-{yyyyMMddHHmm(WIB)}-{6 位大写字母数字}}。 */
    static final Pattern BATCH_NO = Pattern.compile("^GB-\\d{12}-[A-Z0-9]{6}$");
    private static final DateTimeFormatter BATCH_TS = DateTimeFormatter.ofPattern("yyyyMMddHHmm");
    private static final String BATCH_ALPHABET = "ABCDEFGHIJKLMNOPQRSTUVWXYZ0123456789";

    private final GrowthLedgerService ledger;
    private final AdminAuditService audit;
    private final AdminAccountRepository adminAccounts;
    private final TransactionTemplate perUser;
    private final Clock clock;
    private final SecureRandom random = new SecureRandom();

    @Autowired
    public AdminGrowthBatchService(GrowthLedgerService ledger, AdminAuditService audit,
            AdminAccountRepository adminAccounts, PlatformTransactionManager tm) {
        this(ledger, audit, adminAccounts, tm, Clock.systemUTC());
    }

    AdminGrowthBatchService(GrowthLedgerService ledger, AdminAuditService audit, AdminAccountRepository adminAccounts,
            PlatformTransactionManager tm, Clock clock) {
        this.ledger = ledger;
        this.audit = audit;
        this.adminAccounts = adminAccounts;
        this.perUser = new TransactionTemplate(tm);
        this.perUser.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
        this.clock = clock;
    }

    // ------------------------------------------------------------------ 视图模型

    /** 问题行：原始 ID 文本 + 原因（{@code admin.v140.growth.batch.reason.<reason>}）。 */
    public record Problem(String id, String reason) {
    }

    /**
     * 预览（也是确认弹层与执行的归类结果）。{@code excluded} = 虚拟号 + 官方号等不参与者（拍板 2026-10-18：与虚拟号一样列入排除）。
     */
    public record Preview(String batchNo, String campaign, int expPerUser, List<Long> validIds, int invalid,
            int excluded, List<Problem> problems, int moreProblems, String normalizedIds) {

        public int valid() {
            return validIds.size();
        }

        public long total() {
            return (long) validIds.size() * expPerUser;
        }
    }

    /** 执行结果（H4）。{@code operator} = 操作人后台显示名。 */
    public record Result(String batchNo, String campaign, int expPerUser, int granted, int alreadyGranted, int invalid,
            int excluded, int failed, String operator) {

        public long totalExp() {
            return (long) granted * expPerUser;
        }

        public int skipped() {
            return invalid + excluded;
        }

        /** 纯重放：本次没有新入账、也没有失败，但有已发过的。 */
        public boolean replayOnly() {
            return granted == 0 && failed == 0 && alreadyGranted > 0;
        }
    }

    // ------------------------------------------------------------------ 三步

    /** ② 预览：校验 → 解析 → 归类 → 生成批次号。失败 422（留在第 ① 步）。 */
    public Preview preview(String campaign, String expRaw, String pasted, String csv) {
        String name = requireCampaign(campaign);
        int exp = requireExp(expRaw);
        requireCampaignRuleEnabled();
        GrowthBatchParser.Parsed parsed = GrowthBatchParser.parse(pasted, csv);
        return classify(newBatchNo(), name, exp, parsed);
    }

    /** ③ 确认弹层：按隐藏字段重新解析 + 归类；有效 0 → 422。 */
    public Preview prepareConfirm(String batchNo, String campaign, String expRaw, String userIds) {
        Preview p = reclassify(batchNo, campaign, expRaw, userIds);
        if (p.valid() == 0) {
            throw AppException.validation("没有可发放的有效用户").code("admin.err.growth.batch.noValid");
        }
        return p;
    }

    /**
     * ④ 执行：重新解析 + 归类后逐人独立事务入账；单人异常只计 {@code failed}（入账时才发现不可发——如刚被停用——计 {@code invalid}）。
     * 未预期的整体异常（如数据库不可用）直接抛给调用方（不吞成 200）。
     */
    public Result execute(String batchNo, String campaign, String expRaw, String userIds, long adminId) {
        Preview p = reclassify(batchNo, campaign, expRaw, userIds);
        if (!ledger.batchMatches(p.batchNo(), p.campaign(), p.expPerUser())) {
            // 重试 / 手改隐藏字段沿用已有批次号却换了活动名或数额 → 拒绝，批次号只对应一次发放
            throw AppException.validation("该批次号已用于另一组活动名称 / 每人 EXP").code("admin.err.growth.batch.batchMismatch");
        }
        int granted = 0;
        int already = 0;
        int invalid = p.invalid();
        int failed = 0;
        for (Long userId : p.validIds()) {
            try {
                AdminAdjustCommand.Result r = perUser.execute(s -> ledger.adjust(
                        AdminAdjustCommand.batch(userId, p.expPerUser(), p.campaign(), adminId, p.batchNo())));
                if (r != null && r.replayed()) {
                    already++;
                } else {
                    granted++;
                }
            } catch (AppException e) {
                // 执行途中规则被停用 → 计失败（可重试）；其余（如刚被停用 / 注销）→ 计无效
                if ("admin.err.growth.campaignDisabled".equals(e.getMessageCode())) {
                    failed++;
                } else {
                    invalid++;
                }
            } catch (RuntimeException e) {
                failed++;
                log.warn("growth batch grant failed: batchNo={} error={}", p.batchNo(), e.getClass().getSimpleName());
            }
        }
        Result result = new Result(p.batchNo(), p.campaign(), p.expPerUser(), granted, already, invalid, p.excluded(),
                failed, operatorName(adminId));
        if (granted > 0 || failed > 0) {
            String summary = "活动=" + p.campaign() + "；每人=" + p.expPerUser() + "；入账=" + granted + "；合计="
                    + result.totalExp() + "；无效=" + invalid + "；虚拟号/官方号=" + p.excluded() + "；已发过=" + already
                    + "；失败=" + failed;
            try {
                perUser.executeWithoutResult(s -> audit.record(adminId, AuditActions.GROWTH_BATCH_GRANTED,
                        "growth_batch", p.batchNo(), summary));
            } catch (RuntimeException e) {
                // 入账已逐人提交：审计失败不把结果改报成「发放失败」（否则运营重试 = 纯重放，审计反而永远补不上）
                log.error("growth batch audit failed: batchNo={} error={}", p.batchNo(), e.getClass().getSimpleName());
            }
        }
        log.info("growth batch done: batchNo={} granted={} already={} invalid={} excluded={} failed={}", p.batchNo(),
                granted, already, invalid, p.excluded(), failed);
        return result;
    }

    // ------------------------------------------------------------------ 内部

    private Preview reclassify(String batchNo, String campaign, String expRaw, String userIds) {
        if (batchNo == null || !BATCH_NO.matcher(batchNo.trim()).matches()) {
            throw AppException.validation("批次号无效").code("admin.err.growth.batch.batchNoInvalid");
        }
        String name = requireCampaign(campaign);
        int exp = requireExp(expRaw);
        requireCampaignRuleEnabled();
        return classify(batchNo.trim(), name, exp, GrowthBatchParser.parse(userIds, null));
    }

    private Preview classify(String batchNo, String campaign, int exp, GrowthBatchParser.Parsed parsed) {
        Map<Long, Eligibility> status = parsed.ids().isEmpty() ? Map.of() : ledger.classify(parsed.ids());
        List<Long> valid = new ArrayList<>();
        List<Problem> problems = new ArrayList<>();
        int invalid = parsed.malformed().size();
        int excluded = 0;
        for (Long id : parsed.ids()) {
            Eligibility e = status.getOrDefault(id, Eligibility.NOT_FOUND);
            switch (e) {
                case ELIGIBLE -> valid.add(id);
                case VIRTUAL, NOT_PARTICIPATING -> {
                    excluded++;
                    problems.add(new Problem(String.valueOf(id), e.name()));
                }
                default -> {
                    invalid++;
                    problems.add(new Problem(String.valueOf(id), e.name()));
                }
            }
        }
        for (String m : parsed.malformed()) {
            problems.add(new Problem(m, "MALFORMED"));
        }
        int more = Math.max(0, problems.size() - MAX_PROBLEM_ROWS);
        List<Problem> shown = problems.size() > MAX_PROBLEM_ROWS ? problems.subList(0, MAX_PROBLEM_ROWS) : problems;
        return new Preview(batchNo, campaign, exp, List.copyOf(valid), invalid, excluded, List.copyOf(shown), more,
                parsed.normalized());
    }

    /** 批次号：{@code GB-yyyyMMddHHmm(WIB)-XXXXXX}（总长 22 ≤ {@code batch_no VARCHAR(32)}）。 */
    String newBatchNo() {
        StringBuilder sb = new StringBuilder("GB-")
                .append(BATCH_TS.format(clock.instant().atZone(ScheduleWindow.WIB))).append('-');
        for (int i = 0; i < 6; i++) {
            sb.append(BATCH_ALPHABET.charAt(random.nextInt(BATCH_ALPHABET.length())));
        }
        return sb.toString();
    }

    private void requireCampaignRuleEnabled() {
        if (!ledger.campaignEnabled()) {
            throw AppException.validation("「运营活动」规则已停用").code("admin.err.growth.campaignDisabled");
        }
    }

    static String requireCampaign(String raw) {
        if (raw == null || raw.isBlank()) {
            throw AppException.validation("请填写活动名称").code("admin.err.growth.batch.campaignRequired");
        }
        String t = raw.trim();
        if (t.length() > GrowthLedgerService.NOTE_MAX_LENGTH) {
            throw AppException.validation("活动名称不能超过 200 字").code("admin.err.growth.batch.campaignTooLong");
        }
        return t;
    }

    static int requireExp(String raw) {
        try {
            int v = Integer.parseInt(raw == null ? "" : raw.trim());
            if (v > 0) {
                return v;
            }
        } catch (NumberFormatException e) {
            // 落到下面统一 422
        }
        throw AppException.validation("每人 EXP 须为正整数").code("admin.err.growth.batch.expInvalid");
    }

    private String operatorName(long adminId) {
        return adminAccounts.findById(adminId).map(a -> a.getDisplayName()).orElse("#" + adminId);
    }
}
