package com.tailtopia.place.service;

import com.tailtopia.content.moderation.ModerationOutcome;
import com.tailtopia.place.domain.Place;
import com.tailtopia.place.domain.PlacePhoto;
import com.tailtopia.place.domain.PlaceStatus;
import com.tailtopia.place.dto.PlacePhotoContributeRequest;
import com.tailtopia.place.event.PlacePhotosSubmittedEvent;
import com.tailtopia.place.repository.PlacePhotoRepository;
import com.tailtopia.place.repository.PlaceRepository;
import com.tailtopia.shared.error.AppException;
import java.util.ArrayList;
import java.util.List;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 场所照片写入（V1.3.0 batch-b1 Story 1.9 · FR-112.3）。
 *
 * <h2>🔴 谁都能补，不只是标记人（AC1）</h2>
 * 场所是**共享的地点条目**，不是谁的帖子 —— 标记人既不能编辑也不能删除它。
 * 所以这里**没有**"只有标记人能加照片"的判断，而且**不许加** ——
 * 加了就等于把场所变成标记人的私产，与 2026-09-15 那条决策直接冲突。
 *
 * <h2>先发后审（AC3）</h2>
 * 补充的照片落 {@code UNDER_REVIEW}：上传者自己立刻看得见（否则他会以为没传上去），
 * 他人要等过审。判定结果三分：
 * <ul>
 *   <li>PASS → VISIBLE；</li>
 *   <li><b>确定性违规</b>（图审命中高置信违规 / 文本 L1）→ <b>REJECTED</b>（终态）；</li>
 *   <li><b>三方降级</b>（超时 / 配额 / 熔断，也就是"不知道"）→ <b>保持挂起</b>，绝不自动放行。</li>
 * </ul>
 * ⚠️ 第二、三条的区别是有意的：**"确定违规"可以判死，"不知道"不行**
 * （场所评论那条全部保持挂起，是因为文本降级与高危在那个接口上分不开）。
 */
@Service
public class PlacePhotoService {

    /**
     * 一个场所最多 9 张照片 —— 与标记表单同一个上限（{@code PlaceCreateRequest}）。
     *
     * <p>🔴 上限按**整个场所**算，不是按人算：按人算的话十个人各传 9 张，
     * 详情页就变成 90 张图的横滑流，谁也翻不完。
     */
    static final int MAX_PHOTOS_PER_PLACE = 9;

    private final PlaceRepository places;
    private final PlacePhotoRepository photos;
    private final ApplicationEventPublisher events;

    public PlacePhotoService(PlaceRepository places, PlacePhotoRepository photos,
            ApplicationEventPublisher events) {
        this.places = places;
        this.photos = photos;
        this.events = events;
    }

    /**
     * 标记场所时一并落照片（Story 1.3 的那批）。
     *
     * <p>这批**已经过了同步富审核**（`PlaceService.mark` 里连同名称/地址/描述一起送审，
     * 含图审）→ 直接落 VISIBLE，不再走一次异步。
     *
     * @param cleanPass 那次富审核是不是**干净 PASS**。RISKY / DEGRADED 时照样落 VISIBLE
     *                  （Story 1.3 的先发后审口径，不改），但**不给 og:image 资格** ——
     *                  站外预览卡会被平台缓存、运营下架也撤不回来，那个场景下
     *                  "有点像"和"压根没查成"都必须当"不给图"（Story 1.10 · AC5）。
     */
    @Transactional
    public void storeInitialPhotos(long placeId, long uploaderId, List<String> urls,
            boolean cleanPass) {
        int order = 0;
        for (String url : urls) {
            photos.save(PlacePhoto.fromMarking(placeId, uploaderId, url, order++, cleanPass));
        }
    }

    /**
     * 他人补充照片（AC1/AC2/AC3）。
     *
     * @return 新落库的照片行（已是挂起态）
     * @throws AppException 场所不存在 / 已下架 → 404；超出 9 张上限 → 422
     */
    @Transactional
    public List<PlacePhoto> contribute(String placeToken, long uploaderId,
            PlacePhotoContributeRequest req) {
        Place place = places.findByPublicTokenAndStatus(placeToken, PlaceStatus.ACTIVE)
                .orElseThrow(() -> AppException.notFound("场所不存在"));
        List<String> urls = req.photoUrls();

        // 🔴 只数**占着位置**的那些（VISIBLE + 待审）：被判死 / 上传者注销的行谁都看不见，
        // 算进上限会让一个界面上只有 4 张图的场所永远加不进第 5 张，而用户腾不出位置。
        long existing = photos.countOccupyingSlots(place.getId());
        if (existing + urls.size() > MAX_PHOTOS_PER_PLACE) {
            // ⚠️ 这条**客户端也该提前挡住**（按钮置灰 + 剩余张数提示）。
            // 能走到这里的只有"客户端与服务端口径漂了"或并发补充 —— 后者是真实存在的：
            // 两个人同时给同一个场所各传 5 张。
            throw AppException.validation("这个场所的照片已达上限");
        }

        // 排在现有照片之后 —— 首图永远是标记人那张（AD-5 的 OG 预览图也取首图）。
        int next = maxSortOrder(place.getId()) + 1;
        List<PlacePhoto> saved = new ArrayList<>(urls.size());
        for (String url : urls) {
            saved.add(photos.save(PlacePhoto.contributed(place.getId(), uploaderId, url, next++)));
        }

        // 异步送审（先发后审）。事件里只带 id 与 URL —— 不带上传者（审核不需要，
        // 带上只会让它更容易被顺手写进日志）。
        events.publishEvent(new PlacePhotosSubmittedEvent(
                saved.stream().map(PlacePhoto::getId).toList(), urls));
        return saved;
    }

    /**
     * 上传者自删自己补充的照片。
     *
     * <p>🔒 **只允许上传者本人**。⚠️ 标记人也不能删别人补的照片 ——
     * 同「标记人不能删别人的评论」：场所没有主人。违规照片走举报 → 运营下架（AB-17A）。
     */
    @Transactional
    public void deleteOwn(long photoId, long userId) {
        PlacePhoto p = photos.findByIdAndDeletedAtIsNull(photoId)
                .orElseThrow(() -> AppException.notFound("照片不存在"));
        if (p.getUploaderId() == null || p.getUploaderId() != userId) {
            throw AppException.forbidden("无权删除该照片");
        }
        // 🔴 **不许删到零张**（code-review 2026-09-15）：标记场所时照片是必填的（1–9 张），
        // 而场所**不可编辑、不可删除** —— 标记人把自己那批一张张删光，就留下一个永远没有照片、
        // 谁也补不回原样的场所条目（列表首图与 H5 的 OG 预览图一起没了）。
        // 只在"这张现在还对外可见"时才管：删一张待审 / 被拒的图不影响对外的张数。
        if (p.isVisible() && photos.countVisible(p.getPlaceId()) <= 1) {
            throw AppException.validation("场所至少要保留一张照片");
        }
        p.softDelete();
        photos.save(p);
    }

    /** 异步审核：通过 → VISIBLE。幂等。 */
    @Transactional
    public void approve(long photoId) {
        photos.findByIdAndDeletedAtIsNull(photoId).ifPresent(p -> {
            if (p.approveModeration()) {
                photos.save(p);
            }
        });
    }

    /**
     * 异步审核：**确定性违规** → REJECTED（终态，仅上传者可见）。幂等。
     *
     * <p>⚠️ 只给"确定违规"用。三方降级（"不知道"）那一支**什么都不做**（保持挂起）——
     * 让一次超时判死一张可能完全正常的照片，是这里最不该发生的事。
     */
    @Transactional
    public void reject(long photoId) {
        photos.findByIdAndDeletedAtIsNull(photoId).ifPresent(p -> {
            if (p.rejectModeration()) {
                photos.save(p);
            }
        });
    }

    /**
     * 注销级联（NFR-8 / D1/D2）：上传者注销 → 其**补充的**照片对他人不可见。
     *
     * <p>⚠️ **标记时提交的那批不在此列**（{@code is_original}）—— 它们是场所条目本身的资料
     * （首图、OG 预览图都取它），随人一起隐藏会让整个场所变成无图条目。
     * 那批的匿名化由「标记人」那条投影完成（详情页的 `markedBy` 已经会显示"已注销用户"）。
     *
     * @return 实际改动的条数
     */
    @Transactional
    public int deactivateUploaderPhotos(long userId) {
        int changed = 0;
        for (PlacePhoto p : photos.findByUploaderIdAndDeletedAtIsNull(userId)) {
            // 🔴 豁免判据是行上的 `is_original` 列（在 `deactivateUploader()` 里判），
            // **不是**"上传者是不是这个场所的标记人"（code-review 2026-09-15）：
            // 标记人事后也可以给自己标的场所补图，那些属于补充照片、该隐藏。
            // 按人判既判错、又要为每张照片查一次 places（就是本类到处在避免的 N+1）。
            if (p.deactivateUploader()) {
                photos.save(p);
                changed++;
            }
        }
        return changed;
    }

    private int maxSortOrder(long placeId) {
        Integer max = photos.maxSortOrder(placeId);
        return max == null ? -1 : max;
    }

    /** 同步富审核的结果映射（供 {@code PlacePhotoModerationListener} 用，集中在一处）。 */
    static boolean isDefinitelyBlocked(ModerationOutcome outcome) {
        return !outcome.degraded() && switch (outcome.verdict()) {
            case TEXT_BLOCKED, IMAGE_BLOCKED -> true;
            default -> false;
        };
    }
}
