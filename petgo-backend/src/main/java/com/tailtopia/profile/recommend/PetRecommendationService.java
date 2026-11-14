package com.tailtopia.profile.recommend;

import com.tailtopia.auth.service.AccountQueryService;
import com.tailtopia.content.service.ContentService;
import com.tailtopia.profile.domain.PetProfile;
import com.tailtopia.profile.repository.PetProfileRepository;
import com.tailtopia.profile.service.DefaultPetPolicy;
import com.tailtopia.shared.media.AliyunOssClient;
import com.tailtopia.social.read.PetFollowReader;
import com.tailtopia.social.read.UserHideRelationReader;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.OptionalLong;
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
 * <h2>🔴 登录态实时 SQL，不许加缓存层（AC2 / NFR-8）</h2>
 * 索引方案见 {@code V20260915_1727__add_pet_recommendation_index.sql}（两条部分索引，
 * 谓词已下推）。若实测扛不住，AC2 允许的降级<b>只有</b> {@code @Scheduled} 定时算好落表 ——
 * ⚠️ 不得引入任何缓存或中间件。别在这里加 Caffeine / Redis。
 * <p>V1.5.0 onboarding-recall OR-5：<b>唯一例外</b>是游客端点的 10 分钟进程内单值快照
 * （{@link PublicPetRecommendationSnapshot}，PRD 拍板）——只缓存那一份固定的全站第一页，
 * 不是通用缓存层；登录态（按查看者算的）仍一律实时。
 *
 * <h2>V1.5.0 onboarding-recall Story 1.1 · OR-3：三段排序</h2>
 * 查看者默认宠物（{@link DefaultPetPolicy#pick}）定出段序列：同品种 → 同物种其他品种 → 全站（他物种）；
 * 无品种跳过第一段；无宠物 / 游客只有全站段且不加物种条件。段内排序与改动前逐字相同（SQL）。
 * 物种匹配只收口在 {@link #speciesKeyOf}。
 *
 * <h2>取数形态：一条聚合 + 三次批量，与候选数无关</h2>
 * <ol>
 *   <li>① content 侧一条聚合查询拿候选（近 14 天活跃 + 公开记录 ≥3）；</li>
 *   <li>② 批量取宠物档案（筛「有头像」）；</li>
 *   <li>③ 批量判 owner 有效（未注销 <b>且</b> 未封号）+ <b>双向</b>拉黑（走 {@code social.read} 统一出口，AD-7）
 *       + 我已追更的宠物（V1.4.2 social-topic Story 1.3 · D-17，{@code PetFollowReader.followedAmong} 一次批量）；</li>
 *   <li>④ 批量取封面帖（V1.5.0 起带帖子 id / 赞数）+ 查看者已赞（一次批量）。</li>
 * </ol>
 * ⚠️ 别在循环里查任何一样 —— 一页 10 张卡会变成 40 次查询。
 */
@Service
public class PetRecommendationService {

    /** 卡片大图的物理像素宽（与客户端 RecommendedPetCard 的 thumbWidth 480 一致）。 */
    static final int COVER_THUMB_WIDTH_PX = 480;

    /** 卡片左下角小圆头像的物理像素宽。 */
    static final int AVATAR_THUMB_WIDTH_PX = 160;

    /** 候选窗口：近 14 天有新公开 Diary 帖（AC1）。 */
    static final Duration ACTIVE_WINDOW = Duration.ofDays(14);

    /** 入池门槛：公开成长记录条数（AC1）。 */
    static final int MIN_PUBLIC_RECORDS = 3;

    /** 一页最多给几张卡（AC6：2 列网格铺满当前页）。 */
    public static final int DEFAULT_LIMIT = 10;

    /** 游客端点 {@code limit} 上限（V1.5.0 Story 1.1 · OR-2）。 */
    public static final int PUBLIC_MAX_LIMIT = 10;

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
    private final PetFollowReader petFollows;

    public PetRecommendationService(ContentService contentService, PetProfileRepository profiles,
            AccountQueryService accounts, UserHideRelationReader hideRelations,
            PetFollowReader petFollows) {
        this.contentService = contentService;
        this.profiles = profiles;
        this.accounts = accounts;
        this.hideRelations = hideRelations;
        this.petFollows = petFollows;
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
     * 被过滤掉的宠物（没头像 / owner 注销封号 / 互相拉黑 / 档案已删 / 我已追更）是**确定性排除**，
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
     * <p>V1.5.0 起这两条判据按段生效、并支持跨段续取，见 {@link #page}。
     *
     * @param cursor 上一页返回的游标（null = 第一页）
     */
    @Transactional(readOnly = true)
    public RecommendedPetResponse.Page pageFor(long viewerId, int limit,
            PetRecommendCursor cursor, Instant now) {
        int capped = Math.max(1, Math.min(limit, DEFAULT_LIMIT * 5));
        Optional<PetProfile> viewerPet = DefaultPetPolicy.pick(
                profiles.findAllByOwnerIdOrderByCreatedAtAscIdAsc(viewerId));
        List<Tier> tiers = tiersFor(viewerPet.orElse(null));
        return page(OptionalLong.of(viewerId), tiers, capped, cursor, now);
    }

    /**
     * 游客版第一页（V1.5.0 onboarding-recall Story 1.1 · OR-2 / AC6）：只有全站段、不翻页。
     *
     * <p>🔴 查看者维度的过滤（自己 / 已追更 / 双向拉黑）<b>全部跳过</b>——没有查看者；
     * 「有头像」「owner 有效」照做（那是宠物本身能不能被推荐的门槛，与谁在看无关）。
     * {@code coverLikedByViewer} 恒 false。
     * <p>⚠️ 本方法每次都实时查；10 分钟快照在 {@link PublicPetRecommendationSnapshot}（OR-5）。
     */
    @Transactional(readOnly = true)
    public List<RecommendedPetResponse> publicFirstPage(int limit, Instant now) {
        int capped = Math.max(1, Math.min(limit, PUBLIC_MAX_LIMIT));
        return page(OptionalLong.empty(), List.of(Tier.any()), capped, null, now).items();
    }

    /**
     * 三段 keyset 的主循环（V1.5.0 onboarding-recall Story 1.1 · OR-3）。
     *
     * <h2>跨段续取</h2>
     * 从游标所在段（无游标 = 第一段）开始；每段<b>最多取一次</b>候选池：
     * <ul>
     *   <li>该段候选不满一池（{@code candidates < poolSize}）= 该段到底 → 装不满就从下一段第一行接着取；</li>
     *   <li>该段候选满一池但页没装满 = 被过滤吃掉了 → 带游标返回（hasMore=true），下一页从这一段续取
     *       （与改动前「一页只取一池」同一形态，查询次数与候选数无关：最多 3 段 × 4 次批量）。</li>
     * </ul>
     * 游标 = 最后<b>看过</b>那一行 + 它所在的段（理由见 {@link #pageFor} 原注释的两条判据）。
     */
    private RecommendedPetResponse.Page page(OptionalLong viewer, List<Tier> tiers, int capped,
            PetRecommendCursor cursor, Instant now) {
        int poolSize = Math.max(capped * CANDIDATE_MULTIPLIER, MIN_CANDIDATE_POOL);
        int cursorTier = cursor == null ? -1 : cursor.tier();

        List<Picked> picked = new ArrayList<>(capped);
        ContentService.RecommendablePet lastSeen = null;
        int lastSeenTier = -1;
        boolean hasMore = false;
        for (int t = 0; t < tiers.size(); t++) {
            Tier tier = tiers.get(t);
            if (tier.index() < cursorTier) {
                continue; // 游标已越过这一段
            }
            // 只有游标所在那一段带 keyset；之后的段都从第一行取。游标段不在序列里（无品种跳段）→
            // 落到第一个 ≥ 它的段从头取（宁可重复一两只，不漏一整段）。
            // 只有全站一段时（没宠物）排序与 V1.5.0 前完全相同 → 旧串（缺 tier = 段 0）照样按 keyset 续取。
            boolean keysetHere = cursor != null
                    && (tier.index() == cursorTier || tiers.size() == 1);
            ContentService.RecommendCursor keyset = keysetHere
                    ? new ContentService.RecommendCursor(
                            cursor.interactions(), cursor.lastPostedAt(), cursor.petId())
                    : null;

            // ① content 侧候选（已按「最近更新倒序，同日按互动量」排好序）。
            List<ContentService.RecommendablePet> candidates = contentService.findRecommendablePets(
                    now.minus(ACTIVE_WINDOW), MIN_PUBLIC_RECORDS, poolSize, keyset, tier.filter());
            boolean tierExhausted = candidates.size() < poolSize;
            Map<Long, PetProfile> passed = filter(viewer, candidates);

            int need = capped - picked.size();
            int taken = 0;
            int seenIdx = -1;
            for (int i = 0; i < candidates.size() && taken < need; i++) {
                ContentService.RecommendablePet candidate = candidates.get(i);
                seenIdx = i;
                lastSeen = candidate;
                lastSeenTier = tier.index();
                PetProfile pet = passed.get(candidate.petId());
                if (pet != null) {
                    picked.add(new Picked(pet, tier.index()));
                    taken++;
                }
            }
            if (picked.size() >= capped) {
                // 装满了：本段还有没看的行、本段未到底、或后面还有段 → 后面可能还有。
                hasMore = seenIdx < candidates.size() - 1 || !tierExhausted || t < tiers.size() - 1;
                break;
            }
            if (!tierExhausted) {
                // 被过滤吃掉了：池子后面还有候选没看，下一页从这一段续取。
                hasMore = true;
                break;
            }
        }

        if (picked.isEmpty()) {
            // 🛡 这一页全被过滤光了也可能后面还有 —— 带着游标回空页，客户端照旧能往下翻。
            return hasMore && lastSeen != null
                    ? new RecommendedPetResponse.Page(List.of(), cursorOf(lastSeen, lastSeenTier), true)
                    : RecommendedPetResponse.Page.last(List.of());
        }

        // ④ 封面帖（批量）+ 查看者是否已赞（批量，游客跳过）。
        Map<Long, ContentService.CoverPost> covers = contentService.findLatestPublicCoverPosts(
                picked.stream().map(x -> x.pet().getId()).toList());
        Set<Long> liked = viewer.isPresent() && !covers.isEmpty()
                ? contentService.likedAmong(viewer.getAsLong(),
                        covers.values().stream().map(ContentService.CoverPost::postId).toList())
                : Set.of();

        List<RecommendedPetResponse> items = picked.stream()
                .map(x -> toResponse(x.pet(), covers.get(x.pet().getId()), liked,
                        RecommendMatch.ofTier(x.tier()), now))
                .toList();
        return hasMore
                ? new RecommendedPetResponse.Page(items, cursorOf(lastSeen, lastSeenTier), true)
                : RecommendedPetResponse.Page.last(items);
    }

    /**
     * ②③ 档案批量 + owner 两层过滤 + 已追更（各一次批量）；返回通过过滤的 petId → 档案。
     * 查看者为空（游客）时跳过「自己 / 已追更 / 双向拉黑」三条。
     */
    private Map<Long, PetProfile> filter(OptionalLong viewer,
            List<ContentService.RecommendablePet> candidates) {
        if (candidates.isEmpty()) {
            return Map.of();
        }
        // ② 宠物档案（批量）。⚠️ 顺序由调用方按 content 给的顺序遍历 —— 那才是 AC1 的排序口径。
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
        Set<Long> hiddenOwners = viewer.isPresent()
                ? hideRelations.hiddenEitherWay(viewer.getAsLong(), ownerIds) : Set.of();
        // V1.4.2 social-topic Story 1.3 · D-17：已追更的宠物不再推荐 —— 对本页候选**一次批量**，
        // ⚠️ 不在循环里逐个查（一页 40 个候选就是 40 次查询）；不缓存（架构护栏）。
        Set<Long> followedPets = viewer.isPresent()
                ? petFollows.followedAmong(viewer.getAsLong(), petIds) : Set.of();

        Map<Long, PetProfile> passed = new java.util.HashMap<>();
        for (Long petId : petIds) {
            PetProfile pet = byId.get(petId);
            if (pet == null) {
                continue; // 档案已删（内容还在但档案没了）
            }
            // 「有头像」是 AC1 的入池门槛 —— 没头像的卡左下角是个空圈，不如不推。
            if (pet.getAvatarUrl() == null || pet.getAvatarUrl().isBlank()) {
                continue;
            }
            // 不推给自己（自己的宠物不该出现在「逛别人家的」里）。
            if (viewer.isPresent() && pet.getOwnerId() == viewer.getAsLong()) {
                continue;
            }
            // 已追更的宠物不推（D-17）：在关注流里已经能看到它了。
            if (followedPets.contains(petId)) {
                continue;
            }
            if (hiddenOwners.contains(pet.getOwnerId())) {
                continue;
            }
            if (!activeOwners.contains(pet.getOwnerId())) {
                continue;
            }
            passed.put(petId, pet);
        }
        return passed;
    }

    private static RecommendedPetResponse toResponse(PetProfile pet, ContentService.CoverPost cover,
            Set<Long> liked, RecommendMatch match, Instant now) {
        return new RecommendedPetResponse(
                pet.getId(),
                pet.getName(),
                // 🔒 他人宠物的图对外分发一律服务端去 EXIF（防改过的客户端绕过客户端剥离泄漏 GPS），
                //    与他人主页 / 访客视图同口径。用「缩放 + 去 EXIF」一体串：
                //    客户端遇到已带 x-oss-process 的 URL 不再追加缩略图参数，只去 EXIF 会让卡片拉原图。
                AliyunOssClient.exifStrippedThumbUrl(pet.getAvatarUrl(), AVATAR_THUMB_WIDTH_PX),
                pet.getPetType(),
                pet.getBirthday(),
                AliyunOssClient.exifStrippedThumbUrl(cover == null ? null : cover.imageUrl(),
                        COVER_THUMB_WIDTH_PX),
                companionDays(pet.getCreatedAt(), now),
                displayBreed(pet),
                cover == null ? null : cover.postId(),
                cover == null ? 0L : cover.likeCount(),
                cover != null && liked.contains(cover.postId()),
                match);
    }

    /**
     * 查看者默认宠物 → 段序列（OR-3）：有品种 [同品种, 同物种其他品种, 他物种]；无品种 [同物种, 他物种]；
     * 无宠物 [全站（不加物种条件）]。三段互斥，同一只宠物不会出现两次。
     */
    static List<Tier> tiersFor(PetProfile viewerPet) {
        if (viewerPet == null) {
            return List.of(Tier.any());
        }
        String species = speciesKeyOf(viewerPet);
        String breed = breedNorm(viewerPet);
        List<Tier> out = new ArrayList<>(3);
        if (breed != null) {
            out.add(new Tier(0, new ContentService.RecommendFilter(
                    ContentService.RecommendFilterMode.SAME_BREED, species, breed)));
        }
        out.add(new Tier(1, new ContentService.RecommendFilter(
                ContentService.RecommendFilterMode.SAME_SPECIES_OTHER_BREED, species, breed)));
        out.add(new Tier(2, new ContentService.RecommendFilter(
                ContentService.RecommendFilterMode.OTHER_SPECIES, species, null)));
        return out;
    }

    /**
     * 「同物种」匹配键 —— 🔴 唯一收口（OR-3）。
     *
     * <p>现返回 {@code pet_type} 线格式（CAT / DOG / OTHER），SQL 侧按 {@code pp.pet_type} 比较。
     * 物种扩展线（会话 ④）改存储方式时<b>只改这里 + {@code ContentPostRepository.findRecommendablePets}
     * 的过滤列</b>，别处不许自己判物种。
     */
    static String speciesKeyOf(PetProfile pet) {
        return pet.getPetType().name();
    }

    /** 规范化品种 {@code lower(btrim(breed))}（与 SQL 同口径）；空串 / null = 无品种。 */
    static String breedNorm(PetProfile pet) {
        String b = pet.getBreed();
        if (b == null) {
            return null;
        }
        String t = b.strip().toLowerCase(java.util.Locale.ROOT);
        return t.isEmpty() ? null : t;
    }

    private static String displayBreed(PetProfile pet) {
        String b = pet.getBreed();
        if (b == null || b.isBlank()) {
            return null;
        }
        return b.strip();
    }

    /** 候选行 → 对外游标 token（整个排序键 + 段号，见 {@link PetRecommendCursor}）。 */
    private static String cursorOf(ContentService.RecommendablePet row, int tier) {
        return new PetRecommendCursor(row.interactions(), row.lastPostedAt(), row.petId(),
                Math.max(0, tier)).encode();
    }

    /** 一段：段号（游标 / match 用）+ 交给 content 的过滤参数。 */
    record Tier(int index, ContentService.RecommendFilter filter) {
        static Tier any() {
            return new Tier(2, ContentService.RecommendFilter.ANY);
        }
    }

    private record Picked(PetProfile pet, int tier) {
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
