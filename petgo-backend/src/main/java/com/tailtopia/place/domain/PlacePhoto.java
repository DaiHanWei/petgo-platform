package com.tailtopia.place.domain;

import com.tailtopia.content.domain.CommentModerationStatus;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.PrePersist;
import jakarta.persistence.PreUpdate;
import jakarta.persistence.Table;
import java.time.Instant;

/**
 * 场所照片（V1.3.0 batch-b1 Story 1.9 · FR-112.3）。
 *
 * <h2>🔴 它取代了 `places.photo_urls`</h2>
 * Story 1.1 建表时照片只是一个 URL 字符串数组，装不下本 story 要的三样：**上传者**（AC2）、
 * **每张自己的审核态**（AC3 先发后审）、**单张删除**。
 * 后两样在 JSONB 数组上做就是读-改-写整个数组 —— 两张照片同时过审必丢一条。
 * 迁移 {@code V20260915_0927__init_place_photos.sql} 已把存量搬进来并**删掉了那一列**：
 * 单一事实源，不留第二份真相。
 *
 * <h2>两种照片，落库时的审核态不同</h2>
 * <ul>
 *   <li><b>标记场所时一并提交的</b>（Story 1.3）：已经过了同步富审核（含图审）→ 落
 *       {@link CommentModerationStatus#VISIBLE}；</li>
 *   <li><b>事后补充的</b>（本 story）：先发后审 → 落 {@code UNDER_REVIEW}，
 *       过审才对他人可见（上传者自己一直看得见）。</li>
 * </ul>
 */
@Entity
@Table(name = "place_photos")
public class PlacePhoto {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "place_id", nullable = false)
    private Long placeId;

    @Column(name = "uploader_id", nullable = false)
    private Long uploaderId;

    @Column(name = "url", nullable = false, length = 1024)
    private String url;

    @Enumerated(EnumType.STRING)
    @Column(name = "moderation_status", nullable = false, length = 24)
    private CommentModerationStatus moderationStatus = CommentModerationStatus.VISIBLE;

    @Column(name = "sort_order", nullable = false)
    private int sortOrder;

    /**
     * 是不是「标记这个场所时一并提交的那批」。
     *
     * <p>🔴 与「上传者是不是标记人」**不是一回事**：标记人事后也可以给自己标的场所补图，
     * 那些属于补充照片。注销级联豁免的是**前者**（见 {@link #deactivateUploader()}）。
     */
    @Column(name = "is_original", nullable = false)
    private boolean original;

    /**
     * 能不能当**站外分享页的 og:image**（Story 1.10 · AC5）。
     *
     * <p>🔴 **比 {@code moderationStatus = VISIBLE} 更严**，两者不是一回事：
     * 标记场所时那批走"先发后审"——三方 {@code RISKY} / {@code DEGRADED}
     * （"有点像"或"压根没查成"）照样落 VISIBLE 对外展示（Story 1.3 的产品口径，不改）。
     * 而 og:image 会被社交平台**抓取并缓存**，运营下架也撤不回来 ——
     * 那个场景下"没查成"必须当"不给图"。所以只有**干净 PASS** 的图才是 true。
     */
    @Column(name = "og_eligible", nullable = false)
    private boolean ogEligible;

    @Column(name = "deleted_at")
    private Instant deletedAt;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    protected PlacePhoto() {
    }

    /**
     * 标记场所时一并提交的照片：已过同步富审核，直接可见。
     *
     * @param cleanPass 那次富审核是不是**干净 PASS**（不是 RISKY / DEGRADED）。
     *                  只有干净 PASS 的图才能当站外分享页的 og:image —— 见 {@link #isOgEligible()}。
     */
    public static PlacePhoto fromMarking(long placeId, long uploaderId, String url, int sortOrder,
            boolean cleanPass) {
        PlacePhoto p = create(placeId, uploaderId, url, sortOrder,
                CommentModerationStatus.VISIBLE, true);
        p.ogEligible = cleanPass;
        return p;
    }

    /** 事后补充的照片（AC1/AC3）：先发后审 —— 落挂起，过审才对他人可见。 */
    public static PlacePhoto contributed(long placeId, long uploaderId, String url, int sortOrder) {
        return create(placeId, uploaderId, url, sortOrder,
                CommentModerationStatus.UNDER_REVIEW, false);
    }

    private static PlacePhoto create(long placeId, long uploaderId, String url, int sortOrder,
            CommentModerationStatus status, boolean original) {
        PlacePhoto p = new PlacePhoto();
        p.placeId = placeId;
        p.uploaderId = uploaderId;
        p.url = url;
        p.sortOrder = sortOrder;
        p.moderationStatus = status;
        p.original = original;
        return p;
    }

    /**
     * 审核通过：UNDER_REVIEW → VISIBLE。仅挂起态可转（幂等）。
     *
     * <p>走到这里的判定是**干净 PASS**（高危与降级各有自己的分支，见
     * {@code PlacePhotoModerationListener}），所以同时开放 og:image 资格。
     */
    public boolean approveModeration() {
        if (moderationStatus == CommentModerationStatus.UNDER_REVIEW) {
            moderationStatus = CommentModerationStatus.VISIBLE;
            ogEligible = true;
            return true;
        }
        return false;
    }

    /**
     * 审核拒绝（AC3 的另一半）：UNDER_REVIEW → REJECTED（终态，仍仅上传者可见）。
     *
     * <p>⚠️ 与场所**评论**那条有意不同：图片审核的 `IMAGE_BLOCKED` 是**确定性判定**
     * （命中高置信违规图），不是"三方超时所以不知道"—— 确定性的违规可以直接判死。
     * 三方降级（不知道）那一支仍然只是保持挂起，见 {@code PlacePhotoService}。
     */
    public boolean rejectModeration() {
        if (moderationStatus == CommentModerationStatus.UNDER_REVIEW) {
            moderationStatus = CommentModerationStatus.REJECTED;
            return true;
        }
        return false;
    }

    /** 软删（上传者自删 / 运营删）。幂等。 */
    public boolean softDelete() {
        if (deletedAt == null) {
            deletedAt = Instant.now();
            return true;
        }
        return false;
    }

    /**
     * 注销联动（NFR-8 / D1/D2）：上传者注销 → 其**补充的**照片对他人不可见。
     *
     * <p>🔴 {@link #isOriginal()} 的那批**不隐藏** —— 它们是场所条目本身的资料
     * （首图 / OG 预览图都取它），随人一起隐藏会把整个场所变成无图条目。
     * 那批的身份匿名化由详情页的 `markedBy` 投影完成（显示「已注销用户」）。
     */
    public boolean deactivateUploader() {
        if (original) {
            return false;
        }
        if (deletedAt == null
                && moderationStatus != CommentModerationStatus.AUTHOR_DEACTIVATED) {
            moderationStatus = CommentModerationStatus.AUTHOR_DEACTIVATED;
            return true;
        }
        return false;
    }

    public boolean isVisible() {
        return deletedAt == null && moderationStatus == CommentModerationStatus.VISIBLE;
    }

    public Long getId() {
        return id;
    }

    public Long getPlaceId() {
        return placeId;
    }

    public Long getUploaderId() {
        return uploaderId;
    }

    public String getUrl() {
        return url;
    }

    public CommentModerationStatus getModerationStatus() {
        return moderationStatus;
    }

    public int getSortOrder() {
        return sortOrder;
    }

    /** 是不是标记场所时一并提交的那批。 */
    public boolean isOriginal() {
        return original;
    }

    /** 能不能当站外分享页的 og:image（比"对外可见"更严，见字段注释）。 */
    public boolean isOgEligible() {
        return ogEligible;
    }

    public Instant getDeletedAt() {
        return deletedAt;
    }

    @PrePersist
    void onCreate() {
        Instant now = Instant.now();
        createdAt = now;
        updatedAt = now;
    }

    @PreUpdate
    void onUpdate() {
        updatedAt = Instant.now();
    }
}
