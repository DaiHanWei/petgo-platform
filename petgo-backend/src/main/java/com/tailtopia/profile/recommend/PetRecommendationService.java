package com.tailtopia.profile.recommend;

import com.tailtopia.auth.service.AccountQueryService;
import com.tailtopia.content.service.ContentService;
import com.tailtopia.profile.domain.PetProfile;
import com.tailtopia.profile.repository.PetProfileRepository;
import com.tailtopia.social.read.UserHideRelationReader;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 「逛别人家的毛孩子」推荐池（V1.3.0 batch-b1 Story 4.1 · FR-121 · AC1/AC2/AC3）。
 *
 * <h2>🔴 宠物级简单规则，**不复用 FR-95 的内容打分**（AC1）</h2>
 * 那是内容级算法（{@code content.rank.FeedRecommendationService}），这一条是宠物级规则，
 * <b>两套独立</b>。也**零后台依赖** —— 本版不加任何后台配置项，下面几个数就是全部旋钮。
 *
 * <h2>🔴 实时 SQL，不许加缓存层（AC2 / NFR-8）</h2>
 * 索引方案见 {@code V20260915_1727__add_pet_recommendation_index.sql}（两条部分索引，
 * 谓词已下推）。若实测扛不住，AC2 允许的降级<b>只有</b> {@code @Scheduled} 定时算好落表 ——
 * ⚠️ 不得引入任何缓存或中间件。别在这里加 Caffeine / Redis。
 *
 * <h2>取数形态：一条聚合 + 三次批量，与候选数无关</h2>
 * <ol>
 *   <li>① content 侧一条聚合查询拿候选（近 14 天活跃 + 公开记录 ≥3）；</li>
 *   <li>② 批量取宠物档案（筛「有头像」）；</li>
 *   <li>③ 批量判 owner 有效（未注销 <b>且</b> 未封号）+ <b>双向</b>拉黑（走 {@code social.read} 统一出口，AD-7）；</li>
 *   <li>④ 批量取封面图。</li>
 * </ol>
 * ⚠️ 别在循环里查任何一样 —— 一页 10 张卡会变成 40 次查询。
 */
@Service
public class PetRecommendationService {

    /** 候选窗口：近 14 天有新公开 Diary 帖（AC1）。 */
    static final Duration ACTIVE_WINDOW = Duration.ofDays(14);

    /** 入池门槛：公开成长记录条数（AC1）。 */
    static final int MIN_PUBLIC_RECORDS = 3;

    /** 一页最多给几张卡（AC6：2 列网格铺满当前页）。 */
    public static final int DEFAULT_LIMIT = 10;

    /**
     * content 侧多取几倍候选，留给后面三层过滤。
     *
     * <p>🔴 「有头像 / owner 未注销 / 互相拉黑不互推」三条都读不到 content 的表，
     * 只能在本类里做 —— 都发生在 SQL 的 {@code LIMIT} <b>之后</b>。
     * 取多少就展示多少的话，一页会越过滤越空（同 Story 3.1「表里留 50、下发 30」的思路）。
     * ⚠️ 这个系数**不是**性能旋钮，是正确性冗余；调小它的表现是「推荐位有时只有 3 张卡」。
     */
    static final int CANDIDATE_MULTIPLIER = 4;

    /** 冗余的下限：limit 很小时（比如 4-4 的横滑行只要 6 张）乘数不够用。 */
    static final int MIN_CANDIDATE_POOL = 40;

    private final ContentService contentService;
    private final PetProfileRepository profiles;
    private final AccountQueryService accounts;
    private final UserHideRelationReader hideRelations;

    public PetRecommendationService(ContentService contentService, PetProfileRepository profiles,
            AccountQueryService accounts, UserHideRelationReader hideRelations) {
        this.contentService = contentService;
        this.profiles = profiles;
        this.accounts = accounts;
        this.hideRelations = hideRelations;
    }

    /**
     * 推荐给 {@code viewerId} 看的宠物卡（**第一页**，不翻页）。
     *
     * <p>Story 4.1 / 4.2 的两个推荐位用它：一屏铺满就够，没有「加载更多」。
     *
     * @param viewerId 查看者（本接口仅登录可达，故非空）
     * @param limit    要几张卡
     */
    @Transactional(readOnly = true)
    public List<RecommendedPetResponse> recommendFor(long viewerId, int limit, Instant now) {
        return pageFor(viewerId, limit, null, now).items();
    }

    /**
     * 推荐给 {@code viewerId} 看的**一页**宠物卡（Story 4.3 的全屏集合页用它翻页）。
     *
     * <h2>🔴 游标取自「最后**看过**的那一行候选」，不是「最后**返回**的那一张卡」</h2>
     * 被过滤掉的宠物（没头像 / owner 注销封号 / 互相拉黑 / 档案已删）是**确定性排除**，
     * 下一页再扫一遍它们只会再排除一次 —— 所以游标越过它们是对的、也更省。
     * ⚠️ 反过来（游标取自最后返回的卡）不会出错，但每翻一页都要重扫一遍这些必然被丢掉的行。
     *
     * <h2>hasMore 的判据有两条，缺一条就会提前断页</h2>
     * <ol>
     *   <li>这一页**装满了**（{@code picked == capped}）→ 后面可能还有；</li>
     *   <li>没装满，但 content 侧**把候选池给满了**（{@code candidates == poolSize}）→
     *       说明是被过滤吃掉的，池子后面还有候选没看。</li>
     * </ol>
     * 只看第 ① 条的表现是「一页里被过滤掉几个就再也翻不动了」——
     * 而池子越往后拉黑/注销的比例并不会降低。
     *
     * @param cursor 上一页返回的游标（null = 第一页）
     */
    @Transactional(readOnly = true)
    public RecommendedPetResponse.Page pageFor(long viewerId, int limit,
            PetRecommendCursor cursor, Instant now) {
        int capped = Math.max(1, Math.min(limit, DEFAULT_LIMIT * 5));
        int poolSize = Math.max(capped * CANDIDATE_MULTIPLIER, MIN_CANDIDATE_POOL);

        // ① content 侧候选（已按「最近更新倒序，同日按互动量」排好序）。
        List<ContentService.RecommendablePet> candidates = contentService.findRecommendablePets(
                now.minus(ACTIVE_WINDOW), MIN_PUBLIC_RECORDS, poolSize,
                cursor == null ? null : new ContentService.RecommendCursor(
                        cursor.interactions(), cursor.lastPostedAt(), cursor.petId()));
        if (candidates.isEmpty()) {
            return RecommendedPetResponse.Page.last(List.of());
        }

        // ② 宠物档案（批量）。⚠️ 保持 content 给的顺序 —— 那才是 AC1 的排序口径。
        List<Long> petIds = candidates.stream()
                .map(ContentService.RecommendablePet::petId).toList();
        Map<Long, PetProfile> byId = profiles.findAllById(petIds).stream()
                .collect(Collectors.toMap(PetProfile::getId, Function.identity()));

        // ③ owner 维度的两层过滤，各一次批量。
        Set<Long> ownerIds = new LinkedHashSet<>();
        for (Long petId : petIds) {
            PetProfile p = byId.get(petId);
            if (p != null) {
                ownerIds.add(p.getOwnerId());
            }
        }
        // 🔴 已注销**或被封号**的用户，其宠物不入池（AC3）。
        // ⚠️ 判据必须与落地页 PetProfileQueryService#findVisibleProfileById 的 isActive 逐字一致 ——
        //    只判「注销」漏掉「封号」的表现是「推荐位里那只宠物点进去 404」（code-review 2026-09-15）。
        Set<Long> activeOwners = accounts.activeIdsAmong(ownerIds);
        // 互相拉黑的双方不互推（AC3）——**双向**，走统一读取口（AD-7）。
        // ⚠️ 只判单向的表现是「我拉黑了他，他家的宠物还在我的推荐位里」。
        Set<Long> hiddenOwners = hideRelations.hiddenEitherWay(viewerId, ownerIds);

        List<PetProfile> picked = new ArrayList<>(capped);
        // 最后**看过**的那一行候选 —— 下一页的游标从它算（见方法注释）。
        ContentService.RecommendablePet lastSeen = null;
        for (ContentService.RecommendablePet candidate : candidates) {
            if (picked.size() >= capped) {
                break;
            }
            lastSeen = candidate;
            long petId = candidate.petId();
            PetProfile pet = byId.get(petId);
            if (pet == null) {
                continue; // 档案已删（内容还在但档案没了）
            }
            // 「有头像」是 AC1 的入池门槛 —— 没头像的卡左下角是个空圈，不如不推。
            if (pet.getAvatarUrl() == null || pet.getAvatarUrl().isBlank()) {
                continue;
            }
            // 不推给自己（自己的宠物不该出现在「逛别人家的」里）。
            if (pet.getOwnerId() == viewerId) {
                continue;
            }
            if (hiddenOwners.contains(pet.getOwnerId())) {
                continue;
            }
            if (!activeOwners.contains(pet.getOwnerId())) {
                continue;
            }
            picked.add(pet);
        }
        boolean hasMore = picked.size() >= capped || candidates.size() >= poolSize;
        if (picked.isEmpty()) {
            // 🛡 这一页全被过滤光了也可能后面还有 —— 带着游标回空页，客户端照旧能往下翻。
            return hasMore && lastSeen != null
                    ? new RecommendedPetResponse.Page(List.of(), cursorOf(lastSeen), true)
                    : RecommendedPetResponse.Page.last(List.of());
        }

        // ④ 封面图（批量）。没有带图公开帖的宠物拿不到，客户端按占位渲染。
        Map<Long, String> covers = contentService.findLatestPublicCovers(
                picked.stream().map(PetProfile::getId).toList());

        List<RecommendedPetResponse> items = picked.stream()
                .map(pet -> new RecommendedPetResponse(
                        pet.getId(),
                        pet.getName(),
                        pet.getAvatarUrl(),
                        pet.getPetType(),
                        pet.getBirthday(),
                        covers.get(pet.getId()),
                        companionDays(pet.getCreatedAt(), now)))
                .toList();
        return hasMore
                ? new RecommendedPetResponse.Page(items, cursorOf(lastSeen), true)
                : RecommendedPetResponse.Page.last(items);
    }

    /** 候选行 → 对外游标 token（整个排序键，见 {@link PetRecommendCursor}）。 */
    private static String cursorOf(ContentService.RecommendablePet row) {
        return new PetRecommendCursor(row.interactions(), row.lastPostedAt(), row.petId()).encode();
    }

    /**
     * 陪伴天数 = 今天 − 档案创建日（UTC 天数，≥0）。
     *
     * <p>🔴 与 H5 名片**同一个算法**（{@code CardPageController.companionDays}）——
     * 两处算出不同的天数，用户一对比就看得出来。
     * <p>⚠️ 这与 {@code visitor_archive_view.dart} 里「Diary 页不引入陪伴天数」那条注释
     * <b>不冲突</b>：那说的是 Diary <b>档案页</b>，本 story 说的是<b>推荐卡片</b>。
     */
    static long companionDays(Instant createdAt, Instant now) {
        return com.tailtopia.profile.web.CardPageController.companionDays(createdAt, now);
    }
}
