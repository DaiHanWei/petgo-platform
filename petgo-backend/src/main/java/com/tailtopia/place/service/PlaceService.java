package com.tailtopia.place.service;

import com.tailtopia.content.moderation.ModerationOutcome;
import com.tailtopia.content.service.ContentModerationService;
import com.tailtopia.moderation.domain.ReportReason;
import com.tailtopia.place.domain.Place;
import com.tailtopia.place.domain.PlaceReport;
import com.tailtopia.place.domain.PlaceStatus;
import com.tailtopia.place.dto.PlaceCreateRequest;
import com.tailtopia.place.repository.PlaceReportRepository;
import com.tailtopia.place.repository.PlaceRepository;
import com.tailtopia.shared.error.AppException;
import com.tailtopia.shared.ratelimit.IdempotencyService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 场所写入（V1.3.0 batch-b1 Story 1.3 · FR-112.1）。
 *
 * <h2>🔴 本类只有「创建」，永远不会有「编辑」</h2>
 * 本版<b>用户不可修改场所</b>（2026-09-15 拍板）。三层一致：
 * ① 前端表单前置告知 + 详情页无编辑入口；② <b>服务端不提供任何编辑端点</b>（本 story AC5）；
 * ③ 纠错走后台 AB-17A（下架 / 合并重复）。
 * 只做第 ① 层等于留了个半开的口子 —— 有人直接调接口就改了。
 * 要加编辑能力，先回决策日志改口径，不要从这里开始。
 *
 * <p>另外<b>也没有「用户删除自己标记的场所」</b>：同一条决策。用户可以自删的是
 * <b>自己的场所评论</b>（Story 1.7），不是场所本体。
 */
@Service
public class PlaceService {

    private static final Logger log = LoggerFactory.getLogger(PlaceService.class);

    private final PlaceRepository places;
    private final PlaceTokenGenerator tokens;
    private final ContentModerationService moderation;
    private final IdempotencyService idempotency;
    private final PlaceReportRepository reports;
    private final PlacePhotoService photoService;

    public PlaceService(PlaceRepository places, PlaceTokenGenerator tokens,
            ContentModerationService moderation, IdempotencyService idempotency,
            PlaceReportRepository reports, PlacePhotoService photoService) {
        this.places = places;
        this.tokens = tokens;
        this.moderation = moderation;
        this.idempotency = idempotency;
        this.reports = reports;
        this.photoService = photoService;
    }

    /**
     * 标记一个场所（AC1/AC8）。字段级校验由 Bean Validation 在 controller 完成；这里做审核与落库。
     *
     * <h2>审核口径：先发后审（PRD ① / CROSS-STORY F10）</h2>
     * <ul>
     *   <li><b>L1 硬拦截</b>（{@code TEXT_BLOCKED} / {@code IMAGE_BLOCKED}）→ 即时拒绝、不落库。
     *       这一类是词库/三方明确判定的违规内容，放进去等运营删不如不让它进。</li>
     *   <li><b>RISKY / DEGRADED</b> → <b>照常落库</b>。场所<b>没有</b>内容帖那种「挂起待人工」队列
     *       （PRD 明写先发后审、违规由运营处置，运营入口是后台 AB-17A），所以这里不造一个
     *       只有场所用的审核队列出来。</li>
     * </ul>
     * ⚠️ <b>{@code DEGRADED}（三方挂了）在内容帖那边是 fail-closed 挂起，这里是放行</b> ——
     * 这是「先发后审」这条产品口径的直接后果，不是漏写。要改成 fail-closed 属产品决策，
     * 回决策日志谈，别在这里偷偷改。
     *
     * <h2>🔴 幂等是**必须**的，不是加分项</h2>
     * 用户既不能编辑也不能删除自己标记的场所。丢一个 201（弱网下很常见）+ 客户端重试 =
     * <b>一个永久重复的场所条目</b>，只能等运营去后台合并。同 `POST /content-posts` 的范式：
     * 客户端带 {@code Idempotency-Key} 头，同 key 重放取回原来那条。
     *
     * @param idempotencyKey 客户端生成的重放键；为空时退化为无幂等（老客户端）
     * @return 已落库的场所（调用方只对外回 token）
     */
    @Transactional
    public Place mark(long createdBy, PlaceCreateRequest req, String idempotencyKey) {
        // 幂等重放：同 key 已落一条则取回，不重复创建（也不重复过审核、不重复扣限流）。
        var existing = idempotency.findResourceId(idempotencyKey);
        if (existing.isPresent()) {
            return places.findById(existing.get())
                    .orElseThrow(() -> AppException.notFound("场所不存在"));
        }

        // 审核输入 = 用户可写的全部自由文本 + 照片。
        // ⚠️ **文字地址也要过审**：它同样是用户自由输入的一行字，把它漏掉等于留了个
        // 「把违规内容写在地址栏里」的口子。
        String moderatedText = joinForModeration(req.name(), req.addressText(), req.description());
        ModerationOutcome outcome = moderation.evaluate(moderatedText, req.photoUrls());
        switch (outcome.verdict()) {
            case TEXT_BLOCKED -> throw AppException.contentTextBlocked("内容包含不当词汇，请修改后重试");
            case IMAGE_BLOCKED -> throw AppException.contentImageBlocked("图片包含违规内容，请替换后重试");
            default -> {
                // PASS / RISKY / DEGRADED：先发后审，落库后由运营（AB-17A）处置。
                // 🛡 日志只记结论，**不记文本、不记坐标、不记照片 URL**（NFR-4/NFR-5）。
                if (outcome.verdict() != ContentModerationService.Verdict.PASS) {
                    log.info("place marked with non-pass moderation verdict={} degraded={}",
                            outcome.verdict(), outcome.degraded());
                }
            }
        }

        Place place = Place.mark(
                tokens.generate(),
                req.name().trim(),
                req.type(),
                req.distinctTags(),
                req.latitude(),
                req.longitude(),
                req.addressText().trim(),
                blankToNull(req.description()),
                createdBy);
        Place saved = places.save(place);
        // Story 1.9：照片搬到了 place_photos（每张带上传者与自己的审核态）。
        // 这一批**已经在上面过了同步富审核**（连同名称/地址/描述一起送审，含图审），
        // 所以直接落 VISIBLE，不再走一次异步 —— 同一批图审两遍是白花配额。
        photoService.storeInitialPhotos(saved.getId(), createdBy, req.photoUrls());
        // 没带 key 的老客户端：不记幂等（也就不会去拆 saved.getId()）。
        if (idempotencyKey != null && !idempotencyKey.isBlank() && saved.getId() != null) {
            idempotency.store(idempotencyKey, saved.getId());
        }
        return saved;
    }

    /**
     * 举报一个场所（Story 1.5 · AC5）。
     *
     * <p><b>写工单 PENDING 进运营队列，不触发任何自动下架</b>（同内容举报 Story 3.7 / FR-25）。
     * 处置在后台 AB-17A。
     *
     * <p><b>重复举报幂等</b>：同一个人对同一个场所连点五次 → 队列里只有一条。
     * 报错也不行 —— 用户会以为"没举报成功"再点一次。
     *
     * <p>🔴 <b>下架 / 不存在一律 404</b>，与详情同一口径：让两者可区分等于给出「这个 token
     * 曾经存在」这条信息。
     */
    @Transactional
    public void report(String token, long reporterId, ReportReason reason) {
        Place p = places.findByPublicTokenAndStatus(token, PlaceStatus.ACTIVE)
                .orElseThrow(() -> AppException.notFound("场所不存在"));
        if (reports.existsByPlaceIdAndReporterId(p.getId(), reporterId)) {
            return; // 幂等：已举报过，不再写一条。
        }
        try {
            reports.save(PlaceReport.of(p.getId(), reporterId, reason));
        } catch (DataIntegrityViolationException e) {
            // 🔴 `existsBy` 预查挡不住并发：两次点击同时过了预查，后到的那条撞
            // `uq_place_reports_reporter_place` → 500 →「举报失败」，正是上面那句
            // "报错也不行"要避免的结果。与内容举报（ReportService.submit）同样吞掉：
            // 队列里已经有那条工单，本次不新增即为成功。
            log.debug("场所举报并发撞唯一约束，按幂等吞掉");
        }
    }

    /**
     * 拼接送审文本。
     *
     * <p>用换行拼而不是空格：词库里有跨词匹配的规则，空格拼会把「名称尾字 + 地址首字」
     * 意外连成一个命中词（误拦截），换行不会。
     */
    private static String joinForModeration(String name, String address, String description) {
        StringBuilder sb = new StringBuilder();
        appendLine(sb, name);
        appendLine(sb, address);
        appendLine(sb, description);
        return sb.toString();
    }

    private static void appendLine(StringBuilder sb, String s) {
        if (s == null || s.isBlank()) {
            return;
        }
        if (!sb.isEmpty()) {
            sb.append('\n');
        }
        sb.append(s.trim());
    }

    private static String blankToNull(String s) {
        return (s == null || s.isBlank()) ? null : s.trim();
    }
}
