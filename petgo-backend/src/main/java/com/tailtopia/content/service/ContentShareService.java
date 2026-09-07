package com.tailtopia.content.service;

import com.tailtopia.auth.dto.AuthorView;
import com.tailtopia.auth.service.AccountQueryService;
import com.tailtopia.content.domain.ContentPost;
import com.tailtopia.content.domain.ContentVisibility;
import com.tailtopia.content.domain.ContentShare;
import com.tailtopia.content.domain.PostStatus;
import com.tailtopia.content.dto.ContentShareLinkResponse;
import com.tailtopia.content.dto.SharedPostResponse;
import com.tailtopia.content.repository.ContentPostRepository;
import com.tailtopia.content.repository.ContentShareRepository;
import com.tailtopia.profile.service.CardTokenGenerator;
import com.tailtopia.shared.error.AppException;
import java.util.List;
import java.util.Optional;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 单条内容对外分享（Story 9.3 · FR-73）。
 *
 * <p>职责：① 作者为自己的某条内容创建/复用分享 token（{@link #createOrRefresh}，按内容幂等）；
 * ② 公开落地页按 token 取那一条的只读投影（{@link #findSharedPost}）。
 *
 * <p>🔴 <b>私密内容被主动分享后，访客可见</b>（AD-15 Rule 6）。这与「访客浏览整本档案看不到私密内容」
 * 方向相反，且是刻意的：前者是平台自动分发，后者是<b>作者自己按下了分享键</b>。
 * 这条口径不是本 story 新发明的 —— {@code ContentVisibility} 的注释里早已写明
 * 「visibility 约束的是平台自动分发，不约束用户自己按下分享键的行为」（OQ-18，2026-08-03 拍板）。
 * 所以这里<b>不按 visibility 过滤</b>；发现与 Epic 2 不一致时不要去「统一」它们。
 *
 * <p>但下面这些仍然要挡（它们不是"作者的授权"，而是"这条内容已经不该存在"）：
 * 已删除、审核挂起、被举报下架、作者注销 —— 一律收敛到失效，绝不区分原因（防枚举）。
 */
@Service
public class ContentShareService {

    private final ContentPostRepository posts;
    private final ContentShareRepository shares;
    private final CardTokenGenerator tokenGenerator;
    private final AccountQueryService accounts;

    public ContentShareService(ContentPostRepository posts, ContentShareRepository shares,
            CardTokenGenerator tokenGenerator, AccountQueryService accounts) {
        this.posts = posts;
        this.shares = shares;
        this.tokenGenerator = tokenGenerator;
        this.accounts = accounts;
    }

    /**
     * 作者为自己的某条内容创建（或复用）分享链接。
     *
     * <p>护栏：author 取自 JWT（不信任客户端）；非本人 → 404（不用 403，免得成了"这条存在"的探测器）；
     * 已删除 / 非 PUBLISHED → 422（自己都还没公开的内容不该拿到对外链接）。
     */
    @Transactional
    public ContentShareLinkResponse createOrRefresh(long requesterId, long postId) {
        ContentPost post = posts.findById(postId)
                .filter(p -> p.getDeletedAt() == null)
                .orElseThrow(() -> AppException.notFound("内容不存在"));
        if (post.getStatus() != PostStatus.PUBLISHED) {
            throw AppException.validation("该内容当前不可分享");
        }
        // 🔴 **谁能为这条内容建分享链接**（产品 2026-08-27 放开非作者）：
        //
        // | 关系 | 公开内容 | 私密内容 |
        // |---|---|---|
        // | 作者本人 | 可以 | **可以**（AD-15 Rule 6：作者自己按下分享键 = 授权） |
        // | 其他登录用户 | 可以（本次新增） | **不可以** |
        //
        // 放开的理由：信息流里加了分享入口（用户请求），而流里绝大多数是别人的帖 ——
        // 原先「只能分享自己的」会让那个入口在多数卡片上直接报错。
        //
        // 🛡 **这不是把可见性护栏改松**：能被非作者分享的只有**本来就公开**的内容，
        // 分享链接暴露的东西一点没有多出来（那条帖在信息流里人人可见）。
        // 私密内容仍然只有作者本人能分享 —— 那一条正是 Rule 6 的全部依据
        //（「visibility 约束的是平台自动分发，不约束用户自己按下分享键」），
        // 换成别人按下就不成立了，所以这里必须按作者身份分岔，不能一律放开。
        //
        // ⚠️ 404 而不是 403：与本类其它失效分支同一口径 —— 不区分「没这条」/「不让你分享」，
        // 否则可以拿它枚举出「某个 id 是私密内容」。
        if (post.getAuthorId() != requesterId
                && post.getVisibility() != ContentVisibility.PUBLIC) {
            throw AppException.notFound("内容不存在");
        }

        Optional<ContentShare> existing = shares.findByContentPostId(postId);
        if (existing.isPresent()) {
            ContentShare share = existing.get();
            share.touch();
            return new ContentShareLinkResponse(share.getShareToken());
        }

        String token = tokenGenerator.generate();
        try {
            shares.save(ContentShare.create(token, postId));
        } catch (DataIntegrityViolationException e) {
            // 并发双建窗：唯一约束 (content_post_id) 兜底 → 复用已落库的那条。
            ContentShare race = shares.findByContentPostId(postId).orElseThrow(() -> e);
            return new ContentShareLinkResponse(race.getShareToken());
        }
        return new ContentShareLinkResponse(token);
    }

    /**
     * 公开落地页 / 公开 JSON 按 token 取那一条。
     *
     * <p>失效一律返回 empty（不存在 / 已删 / 审核挂起 / 下架 / 作者注销 都是同一个结果），
     * 由调用方收敛到失效页 —— <b>绝不区分原因</b>，否则这个端点就成了内容状态探测器。
     */
    @Transactional(readOnly = true)
    public Optional<SharedPostResponse> findSharedPost(String shareToken) {
        return shares.findByShareToken(shareToken)
                .flatMap(s -> posts.findById(s.getContentPostId()))
                .filter(p -> p.getDeletedAt() == null)
                .filter(p -> p.getStatus() == PostStatus.PUBLISHED)
                .filter(p -> p.getReportHiddenAt() == null)
                .filter(p -> accounts.isActive(p.getAuthorId()))
                // 🔴 这里**没有** visibility 过滤，是刻意的（AD-15 Rule 6，见类注释）。
                .map(this::project);
    }

    /**
     * 账号注销级联（Story 7.3 / F14「分享链接立即失效」口径）：删除该作者全部内容的分享行。
     *
     * <p>{@link #findSharedPost} 的 {@code isActive} 过滤已让链接在注销后即刻失效，
     * 这里再删行是让 token 本身也不复存在（分享链接不该比内容活得久，与
     * {@code deleteByContentPostIdIn} 的既有口径一致）。幂等可重跑。
     */
    @Transactional
    public void deleteByAuthorForAccountDeletion(long authorId) {
        shares.deleteByAuthorId(authorId);
    }

    private SharedPostResponse project(ContentPost post) {
        // 作者投影走 AccountQueryService（content 不直 join users，Story 3.2 的既有约定）。
        // 注销匿名化也在那一层，本处不重复实现。
        AuthorView author = accounts.findAuthorViews(List.of(post.getAuthorId()))
                .getOrDefault(post.getAuthorId(), AuthorView.anonymized(post.getAuthorId()));
        List<String> images = post.getImageUrls() == null ? List.of() : List.copyOf(post.getImageUrls());
        return new SharedPostResponse(
                author.nickname(),
                author.avatarUrl(),
                author.deleted(),
                post.getType().name(),
                post.getText(),
                images,
                post.getCreatedAt());
    }
}
