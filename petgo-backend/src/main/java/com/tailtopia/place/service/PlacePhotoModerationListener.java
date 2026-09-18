package com.tailtopia.place.service;

import com.tailtopia.content.moderation.ModerationOutcome;
import com.tailtopia.content.service.ContentModerationService;
import com.tailtopia.content.service.ContentModerationService.Verdict;
import com.tailtopia.place.event.PlacePhotosSubmittedEvent;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

/**
 * 补充照片的异步审核（V1.3.0 batch-b1 Story 1.9 · AC3）。
 * {@code @Async @TransactionalEventListener(AFTER_COMMIT)} —— 与内容图审同一个
 * {@link ContentModerationService#evaluate(String, List)}。
 *
 * <h2>判定三分（与场所评论那条有意不同）</h2>
 * <ul>
 *   <li><b>PASS</b> → VISIBLE，对所有人可见，且有 og:image 资格；</li>
 *   <li><b>RISKY</b>（"有点像"）→ 同样 VISIBLE（先发后审，与标记时那批同一口径），
 *       但<b>没有 og:image 资格</b> —— 站外预览卡被平台缓存后撤不回来（产品 2026-09-18 拍板）。</li>
 *   <li><b>确定性违规</b>（图审命中高置信违规）→ <b>REJECTED</b>（终态，仅上传者可见）；</li>
 *   <li><b>三方降级</b>（超时 / 配额 / 熔断 / 异常，也就是"不知道"）→ <b>保持挂起</b>，
 *       绝不自动放行。</li>
 * </ul>
 * ⚠️ 第二条是场所评论那边**没有**的：文本审核接口把"高危"和"降级"混在一个判定里，
 * 分不开就只能全部挂起；图审这里能分开 —— <b>"确定违规"可以判死，"不知道"不行</b>。
 *
 * <p>与评论同样的待办：**运营人工队列尚未覆盖场所资源**（队列的 {@code content_type} 只有
 * CONTENT_POST / COMMENT，且 admin 的处置动作直接打 content 侧的 service）。
 * 所以降级挂起的照片目前需要人工从库里捞 —— 见 story 的待办。
 */
@Component
public class PlacePhotoModerationListener {

    private static final Logger log = LoggerFactory.getLogger(PlacePhotoModerationListener.class);

    private final ContentModerationService moderation;
    private final PlacePhotoService photos;

    public PlacePhotoModerationListener(ContentModerationService moderation,
            PlacePhotoService photos) {
        this.moderation = moderation;
        this.photos = photos;
    }

    @Async
    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    public void onPhotosSubmitted(PlacePhotosSubmittedEvent event) {
        ModerationOutcome outcome;
        try {
            // 纯图审：文本传空串（这批照片没有随附文案）。
            outcome = moderation.evaluate("", event.urls());
        } catch (RuntimeException e) {
            // fail-closed：审核异常 → 保持挂起。只记异常类型，**绝不记 URL**（公开桶 URL 也是数据）。
            log.warn("场所照片审核异常，fail-closed 保持挂起 count={}：{}",
                    event.photoIds().size(), e.getClass().getSimpleName());
            return;
        }

        if (PlacePhotoService.isDefinitelyBlocked(outcome)) {
            // 确定性违规 → 整批判死。⚠️ 三方对一组图返回的是一个结论，
            // 所以这里无法只判其中一张；批内"连坐"是接口形态决定的，不是偷懒。
            event.photoIds().forEach(photos::reject);
            log.info("场所照片命中违规，整批拒绝 count={}", event.photoIds().size());
            return;
        }
        if (outcome.degraded()) {
            log.info("场所照片审核降级，保持挂起 count={}", event.photoIds().size());
            return; // "不知道" → 挂着，绝不自动放行
        }
        // 走到这里只剩 PASS 与 RISKY：都放行可见，但 🔴 **只有干净 PASS 给 og:image 资格**。
        boolean cleanPass = outcome.verdict() == Verdict.PASS;
        if (!cleanPass) {
            log.info("场所照片审核存疑，放行但不作预览图 count={} verdict={}",
                    event.photoIds().size(), outcome.verdict());
        }
        event.photoIds().forEach(id -> photos.approve(id, cleanPass));
    }
}
