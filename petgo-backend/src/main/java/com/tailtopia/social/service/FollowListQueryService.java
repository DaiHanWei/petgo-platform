package com.tailtopia.social.service;

import com.tailtopia.auth.dto.AuthorView;
import com.tailtopia.auth.domain.User;
import com.tailtopia.auth.service.AccountQueryService;
import com.tailtopia.shared.error.AppException;
import com.tailtopia.shared.paging.KeysetCursor;
import com.tailtopia.social.domain.HideSource;
import com.tailtopia.social.domain.UserFollow;
import com.tailtopia.social.dto.FollowListItem;
import com.tailtopia.social.dto.FollowListPage;
import com.tailtopia.social.read.FollowReader;
import com.tailtopia.social.read.FollowState;
import com.tailtopia.social.read.UserHideRelationReader;
import com.tailtopia.social.repository.UserFollowRepository;
import com.tailtopia.social.repository.UserHideRelationRepository;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 关注 / 粉丝列表（V1.3.4 follow · Story 2.2 · AD-4 / AD-16）。
 *
 * <p><b>整页批量</b>：展示字段、token、关注关系、拉黑标记各一次查询，禁止逐行（NFR3）。
 * social 不 join {@code users}：展示字段与 token 一律经 {@link AccountQueryService}。
 *
 * <p><b>拉黑规则（D-10）</b>：我拉黑了主页用户 → 403 {@code blocked-user}（与主页 / 内容区同口径，各端点各拦一次）；
 * 主页用户拉黑了我 → 照常返回真实列表。列表本身<b>不做隐藏过滤</b>（我举报隐藏的人、拉黑了我的人照常列出），
 * 只有我拉黑的人那一行 {@code blockedByMe=true}（App 不显示按钮）。
 *
 * <p>封号用户照常列出（D-8，点进去主页显示不存在）；只有查看者已关注他时才给 token（可取关），
 * 未关注则不给 token、App 不显示按钮（2026-10-13 决定）。已注销用户的关注行已被注销流程删除。
 */
@Service
public class FollowListQueryService {

    /** 每页条数（AD-4）。 */
    static final int PAGE_SIZE = 20;

    private final UserFollowRepository follows;
    private final AccountQueryService accounts;
    private final FollowReader followReader;
    private final UserHideRelationReader hideRelations;
    private final UserHideRelationRepository hideRepo;

    public FollowListQueryService(UserFollowRepository follows, AccountQueryService accounts,
            FollowReader followReader, UserHideRelationReader hideRelations,
            UserHideRelationRepository hideRepo) {
        this.follows = follows;
        this.accounts = accounts;
        this.followReader = followReader;
        this.hideRelations = hideRelations;
        this.hideRepo = hideRepo;
    }

    /** {@code ownerToken} 关注的人。 */
    @Transactional(readOnly = true)
    public FollowListPage following(String ownerToken, Long viewerId, String cursor) {
        long ownerId = resolveOwner(ownerToken, viewerId);
        KeysetCursor c = parseCursor(cursor);
        List<UserFollow> rows = follows.findFollowingPage(ownerId, c.createdAt(), c.id(), PAGE_SIZE + 1);
        return assemble(rows, UserFollow::getFolloweeId, viewerId);
    }

    /** 关注 {@code ownerToken} 的人。 */
    @Transactional(readOnly = true)
    public FollowListPage followers(String ownerToken, Long viewerId, String cursor) {
        long ownerId = resolveOwner(ownerToken, viewerId);
        KeysetCursor c = parseCursor(cursor);
        List<UserFollow> rows = follows.findFollowersPage(ownerId, c.createdAt(), c.id(), PAGE_SIZE + 1);
        return assemble(rows, UserFollow::getFollowerId, viewerId);
    }

    /** 主页用户：不存在 / 已注销 / 封号 → 404（三者不可区分）；我拉黑了他 → 403 blocked-user（D-10）。 */
    private long resolveOwner(String ownerToken, Long viewerId) {
        User owner = accounts.findActiveByPublicToken(ownerToken)
                .orElseThrow(() -> AppException.notFound("用户不存在"));
        long ownerId = owner.getId();
        if (viewerId != null && hideRelations.isBlocked(viewerId, ownerId)) {
            throw AppException.blockedUser("你已拉黑该用户");
        }
        return ownerId;
    }

    private FollowListPage assemble(List<UserFollow> rows, Function<UserFollow, Long> other, Long viewerId) {
        boolean hasMore = rows.size() > PAGE_SIZE;
        List<UserFollow> page = hasMore ? rows.subList(0, PAGE_SIZE) : rows;
        if (page.isEmpty()) {
            return new FollowListPage(List.of(), null, false);
        }
        List<Long> ids = page.stream().map(other).toList();
        Map<Long, AuthorView> views = accounts.findAuthorViewsWithoutTags(ids);
        // 封号也给 token（D-8：可取关）；已注销不给。
        Map<Long, String> tokens = accounts.publicTokensOf(ids, true);
        // 2026-10-13 决定（2.2 复审 F2）：封号用户只有「我已关注」时才给 token（App 据此显示按钮、可取关）；
        // 我没关注的封号用户不给 token → 不显示「Ikuti」（点了必 404）。
        Set<Long> active = accounts.activeIdsAmong(ids);
        Map<Long, FollowState> states = viewerId == null ? Map.of() : followReader.followStates(viewerId, ids);
        Set<Long> blocked = viewerId == null ? Set.of()
                : new HashSet<>(hideRepo.findTargetIdsByHolderAndSourceIn(viewerId, HideSource.BLOCK, ids));
        List<FollowListItem> items = page.stream().map(f -> {
            long id = other.apply(f);
            AuthorView v = views.getOrDefault(id, AuthorView.anonymized(id));
            FollowState s = states.getOrDefault(id, FollowState.NONE);
            boolean deleted = v.deleted();
            boolean actionable = active.contains(id) || s.iFollow();
            return new FollowListItem(id,
                    deleted || !actionable ? null : tokens.get(id),
                    deleted ? null : v.nickname(),
                    deleted ? null : v.avatarUrl(),
                    deleted,
                    s.iFollow(),
                    s.followsMe(),
                    blocked.contains(id),
                    viewerId != null && viewerId == id);
        }).toList();
        UserFollow last = page.get(page.size() - 1);
        String next = hasMore ? new KeysetCursor(last.getCreatedAt(), other.apply(last)).encode() : null;
        return new FollowListPage(items, next, hasMore);
    }

    /** 坏游标按首页处理、不报错（同 {@code NotificationCenterService.parseCursor}）。 */
    private static KeysetCursor parseCursor(String cursor) {
        KeysetCursor parsed = KeysetCursor.decodeOrNull(cursor);
        return parsed != null ? parsed : KeysetCursor.firstPage();
    }
}
