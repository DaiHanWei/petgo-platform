package com.tailtopia.social.service;

import com.tailtopia.auth.domain.Role;
import com.tailtopia.auth.domain.User;
import com.tailtopia.auth.dto.AuthorView;
import com.tailtopia.auth.service.AccountQueryService;
import com.tailtopia.profile.domain.PetProfile;
import com.tailtopia.profile.repository.PetProfileRepository;
import com.tailtopia.shared.error.AppException;
import com.tailtopia.shared.media.AliyunOssClient;
import com.tailtopia.shared.paging.KeysetCursor;
import com.tailtopia.shared.ratelimit.RedisRateLimiter;
import com.tailtopia.social.domain.PetFollow;
import com.tailtopia.social.dto.FollowedPetItem;
import com.tailtopia.social.dto.FollowedPetPage;
import com.tailtopia.social.dto.PetFollowResultResponse;
import com.tailtopia.social.event.PetFollowedEvent;
import com.tailtopia.social.read.FollowReader;
import com.tailtopia.social.read.FollowState;
import com.tailtopia.social.read.UserHideRelationReader;
import com.tailtopia.social.repository.PetFollowRepository;
import java.time.Clock;
import java.time.Duration;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 追更宠物 / 取消追更 / 某用户追更的宠物列表（V1.4.2 social-topic · Story 1.1 · D-1 / D-8 / D-11）。
 *
 * <p><b>拒绝顺序</b>（追更，照 {@link FollowService#follow}）：限流（与关注人共桶）→ 本人非 active 401 →
 * 宠物不存在 / 主人已注销或封号 404 → 自己的宠物 422 → 我拉黑了主人 403 {@code blocked-user} →
 * 已追更直接返回（幂等，不计额度）→ 每日额度（两表合计）429 → 写入。
 * <b>主人拉黑了我照常成功</b>（不暴露被拉黑，通知抑制在 1.2）；{@code REPORT} 来源不拦。READ_ONLY 宠物可被追更。
 *
 * <p><b>计数</b>：追更行 {@code INSERT … ON CONFLICT DO NOTHING} / {@code DELETE} 受影响行数 = 1 时，
 * 才在同一事务内对 {@code pet_profiles.follower_count} 条件 ±1 —— 并发双击只算一次。
 *
 * <p><b>模块边界</b>：读宠物 / 改计数直接用 {@link PetProfileRepository}（同 {@code FollowSuggestionService} 先例），
 * 不另起 profile 侧端口；social 侧碰 {@code pet_profiles} 的 SQL 只在 {@code PetFollowRepository} 两条 CTE。
 *
 * <p>🛡 日志只记内部 id，不记 token / 昵称 / 宠物名。
 */
@Service
public class PetFollowService {

    /** 列表每页条数（同 {@link FollowListQueryService#PAGE_SIZE}）。 */
    static final int PAGE_SIZE = 20;

    /** 列表头像缩略宽度（同 {@code PetRecommendationService.AVATAR_THUMB_WIDTH_PX}）。 */
    static final int AVATAR_THUMB_WIDTH_PX = 160;

    private final PetFollowRepository petFollows;
    private final PetProfileRepository pets;
    private final AccountQueryService accounts;
    private final UserHideRelationReader hideRelations;
    private final FollowReader followReader;
    private final RedisRateLimiter rateLimiter;
    private final FollowDailyQuota dailyQuota;
    private final ApplicationEventPublisher events;
    private final Clock clock;

    @Autowired
    public PetFollowService(PetFollowRepository petFollows, PetProfileRepository pets,
            AccountQueryService accounts, UserHideRelationReader hideRelations, FollowReader followReader,
            RedisRateLimiter rateLimiter, FollowDailyQuota dailyQuota, ApplicationEventPublisher events) {
        this(petFollows, pets, accounts, hideRelations, followReader, rateLimiter, dailyQuota, events,
                Clock.systemUTC());
    }

    PetFollowService(PetFollowRepository petFollows, PetProfileRepository pets,
            AccountQueryService accounts, UserHideRelationReader hideRelations, FollowReader followReader,
            RedisRateLimiter rateLimiter, FollowDailyQuota dailyQuota, ApplicationEventPublisher events,
            Clock clock) {
        this.petFollows = petFollows;
        this.pets = pets;
        this.accounts = accounts;
        this.hideRelations = hideRelations;
        this.followReader = followReader;
        this.rateLimiter = rateLimiter;
        this.dailyQuota = dailyQuota;
        this.events = events;
        this.clock = clock;
    }

    /** 追更（幂等）。 */
    @Transactional
    public PetFollowResultResponse follow(long viewerId, long petId) {
        checkRate(viewerId);
        // 🔴 调用方本人必须仍有效：注销后残留的 access token 不能再长出新的追更行（purgeFollower 之后无人清理）。
        if (!accounts.isActive(viewerId)) {
            throw AppException.unauthorized("登录已失效，请重新登录");
        }
        // 宠物不存在 / 主人已注销 / 主人封号 → 同一个 404，不区分原因。
        PetProfile pet = pets.findById(petId)
                .filter(p -> accounts.isActive(p.getOwnerId()))
                .orElseThrow(() -> AppException.notFound("宠物不存在"));
        long ownerId = pet.getOwnerId();
        if (ownerId == viewerId) {
            throw AppException.validation("不能追更自己的宠物");
        }
        // ⚠️ 只认「我 BLOCK 了主人」→ 403 blocked-user（与站内访客页同 type，拍板点 ①）；
        //    主人拉黑我照常往下走（不暴露被拉黑），举报隐藏不拦。
        if (hideRelations.isBlocked(viewerId, ownerId)) {
            throw AppException.blockedUser("你已拉黑该用户");
        }
        if (!petFollows.existsByFollowerIdAndPetProfileId(viewerId, petId)) {
            dailyQuota.check(viewerId);
            if (petFollows.insertIfAbsent(viewerId, petId) == 1) {
                pets.addFollowerCount(petId, 1);
                // 只在真正新建时发（并发双击只发一次）；主人拉黑了我也照发，抑制在通知侧（D-4 / D-10）。
                events.publishEvent(new PetFollowedEvent(viewerId, petId, ownerId, clock.instant()));
            }
        }
        return new PetFollowResultResponse(true, followerCountOf(petId));
    }

    /**
     * 取消追更（幂等，未追更时静默成功）。<b>不发任何事件 / 通知</b>（PRD「取消追更不发任何消息」）。
     *
     * <p>⚠️ <b>不校验主人状态、不校验拉黑</b>：主人封号期间也必须能取消（PRD AC11），否则这一行永远删不掉。
     * 宠物不存在（已删 → 追更行已级联消失）→ 404（拍板点 ②）。
     */
    @Transactional
    public PetFollowResultResponse unfollow(long viewerId, long petId) {
        checkRate(viewerId);
        if (!pets.existsById(petId)) {
            throw AppException.notFound("宠物不存在");
        }
        if (petFollows.deleteRelation(viewerId, petId) == 1) {
            pets.addFollowerCount(petId, -1);
        }
        return new PetFollowResultResponse(false, followerCountOf(petId));
    }

    /**
     * 拉黑联动（D-11）：删除 {@code holder} 对 {@code target} 名下所有宠物的追更行、对应宠物计数各 −1。
     * 由 {@code UserHideRelationService.block} 在插入 BLOCK 行的<b>同一事务</b>里、{@code followService.removeOnBlock}
     * 之后调用（REQUIRED 加入外层事务）。
     *
     * <p>⚠️ {@code target → holder} 宠物的追更不动；<b>不限流、不发任何事件 / 通知</b>；
     * 重复拉黑删 0 行无副作用；解除拉黑不恢复；举报隐藏不调本方法。
     */
    @Transactional
    public void removeOnBlock(long holderId, long targetId) {
        petFollows.deleteByFollowerAndOwner(holderId, targetId);
    }

    /**
     * 注销清理追更者一侧（D-11 / AC7.2，幂等可重跑）：他追更的每只宠物计数 −1，再删他的全部追更行（一条原子 CTE）。
     *
     * <p>⚠️ <b>不能依赖 FK 的 ON DELETE CASCADE</b>：注销是就地匿名化，{@code users} 行不删，CASCADE 永远不触发。
     * 封号不调用本方法（不清理、不改计数）。
     */
    @Transactional
    public void purgeFollower(long userId) {
        petFollows.purgeFollower(userId);
    }

    /**
     * {@code ownerToken} 用户追更的宠物（AC8）。keyset {@code created_at DESC, pet_profile_id DESC}，每页 20；
     * 坏游标按首页。整页各一次批量查询（宠物、主人投影、token、我的追更集合、我的关注集合），禁止 N+1。
     *
     * <p>主人封号（DEACTIVATED）的宠物照常列出（PRD AC11，可取消）；主人注销 / 宠物删除的行已因级联不存在。
     */
    @Transactional(readOnly = true)
    public FollowedPetPage followingOf(String ownerToken, Long viewerId, String cursor) {
        long userId = resolveOwner(ownerToken, viewerId);
        boolean firstPage = cursor == null || cursor.isBlank();
        KeysetCursor c = parseCursor(cursor);
        List<PetFollow> rows = petFollows.findFollowingPage(userId, c.createdAt(), c.id(), PAGE_SIZE + 1);
        Long petFollowingCount = firstPage ? petFollows.countByFollowerId(userId) : null;

        boolean hasMore = rows.size() > PAGE_SIZE;
        List<PetFollow> page = hasMore ? rows.subList(0, PAGE_SIZE) : rows;
        if (page.isEmpty()) {
            return new FollowedPetPage(List.of(), null, false, petFollowingCount);
        }
        List<Long> petIds = page.stream().map(PetFollow::getPetProfileId).toList();
        Map<Long, PetProfile> petById = pets.findAllById(petIds).stream()
                .collect(Collectors.toMap(PetProfile::getId, Function.identity()));
        List<Long> ownerIds = petById.values().stream().map(PetProfile::getOwnerId).distinct().toList();
        Map<Long, AuthorView> owners = ownerIds.isEmpty() ? Map.of() : accounts.findAuthorViewsWithoutTags(ownerIds);
        // 封号也给 token（供埋点 owner_id，R-2）；已注销不给（其宠物行此时已级联删除，正常不会出现）。
        Map<Long, String> tokens = ownerIds.isEmpty() ? Map.of() : accounts.publicTokensOf(ownerIds, true);
        Set<Long> myPets = viewerId == null ? Set.of() : myFollowedPets(viewerId, petIds);
        Map<Long, FollowState> ownerStates = viewerId == null || ownerIds.isEmpty() ? Map.of()
                : followReader.followStates(viewerId, ownerIds);

        List<FollowedPetItem> items = petIds.stream()
                .map(petById::get)
                .filter(Objects::nonNull) // 读到行与读宠物之间宠物被删：跳过这一行，不 500
                .map(p -> {
                    AuthorView owner = owners.get(p.getOwnerId());
                    return new FollowedPetItem(
                            p.getId(),
                            p.getName(),
                            p.getPetType(),
                            // 🔒 他人宠物的图一律服务端缩放 + 去 EXIF（同 PetRecommendationService）。
                            AliyunOssClient.exifStrippedThumbUrl(p.getAvatarUrl(), AVATAR_THUMB_WIDTH_PX),
                            owner == null || owner.deleted() ? null : owner.nickname(),
                            tokens.get(p.getOwnerId()),
                            myPets.contains(p.getId()),
                            ownerStates.getOrDefault(p.getOwnerId(), FollowState.NONE).iFollow(),
                            p.getPetSpecies() == null ? null : p.getPetSpecies().name());
                })
                .toList();
        PetFollow last = page.get(page.size() - 1);
        String next = hasMore ? new KeysetCursor(last.getCreatedAt(), last.getPetProfileId()).encode() : null;
        return new FollowedPetPage(items, next, hasMore, petFollowingCount);
    }

    private Set<Long> myFollowedPets(long viewerId, List<Long> petIds) {
        return new HashSet<>(petFollows.findFollowedPetIdsAmong(viewerId, petIds));
    }

    /**
     * 主页用户（照抄 {@code FollowListQueryService.resolveOwner}）：不存在 / 已注销 / 封号 → 404；
     * 官方号（ADMIN）且非本人 → 403；我拉黑了他 → 403 {@code blocked-user}。
     */
    private long resolveOwner(String ownerToken, Long viewerId) {
        User owner = accounts.findActiveByPublicToken(ownerToken)
                .orElseThrow(() -> AppException.notFound("用户不存在"));
        long ownerId = owner.getId();
        if (owner.getRole() == Role.ADMIN && (viewerId == null || viewerId != ownerId)) {
            throw AppException.forbidden("该账号的关注列表不对外开放");
        }
        if (viewerId != null && hideRelations.isBlocked(viewerId, ownerId)) {
            throw AppException.blockedUser("你已拉黑该用户");
        }
        return ownerId;
    }

    private long followerCountOf(long petId) {
        Integer v = pets.findFollowerCount(petId);
        return v == null ? 0L : v;
    }

    private void checkRate(long viewerId) {
        rateLimiter.check(FollowService.rateKey(viewerId), FollowService.PER_MINUTE_LIMIT, Duration.ofMinutes(1));
    }

    /** 坏游标按首页处理、不报错（同 {@code FollowListQueryService.parseCursor}）。 */
    private static KeysetCursor parseCursor(String cursor) {
        KeysetCursor parsed = KeysetCursor.decodeOrNull(cursor);
        return parsed != null ? parsed : KeysetCursor.firstPage();
    }
}
