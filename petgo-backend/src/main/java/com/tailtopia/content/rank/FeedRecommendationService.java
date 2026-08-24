package com.tailtopia.content.rank;

import com.tailtopia.content.domain.ContentPost;
import com.tailtopia.content.domain.ContentVisibility;
import com.tailtopia.content.repository.CommentRepository;
import com.tailtopia.content.repository.ContentLikeRepository;
import com.tailtopia.content.repository.ContentPostRepository;
import com.tailtopia.content.service.ContentTagQueryService;
import com.tailtopia.content.species.ContentSpeciesResolver;
import com.tailtopia.content.species.ResolvedSpecies;
import com.tailtopia.profile.domain.PetProfile;
import com.tailtopia.profile.repository.PetProfileRepository;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 推荐序取数与翻页（V1.1.6 Story 16.3）。
 *
 * <p>把三块已有的东西接起来：候选池查询（本 story）、序列快照与曝光记录（16.1）、
 * 打分与配比引擎（16.2）。🛡 <b>本类不做打分</b> —— 那是引擎的事，这里只负责喂数据与切页。
 *
 * <h2>翻页契约</h2>
 * 冷启动 / 下拉刷新 → 新种子 → 一次算出前 {@link FeedRankProperties#sequenceLength()} 条并写快照；
 * 翻页只按游标读快照，🛡 <b>不重算分数</b>。游标超出已缓存长度 → 用<b>同一种子</b>续算下一段。
 *
 * <h2>🔴 从快照读回来的 id 必须重新过一遍过滤</h2>
 * 快照有 30 分钟寿命，期间内容可能被删 / 被挂起 / 转私密，或查看者刚拉黑了某个作者。
 * 所以读页走 {@link ContentPostRepository#findRankableByIds}，而不是 {@code findAllById} ——
 * AC4 明写「任何级别下候选池的全部过滤都不得被绕过」。
 */
@Service
public class FeedRecommendationService {

    private static final org.slf4j.Logger log =
            org.slf4j.LoggerFactory.getLogger(FeedRecommendationService.class);

    /**
     * 每页最多向序列多要几条，用来补齐「读回来发现已不合格」的空缺。
     *
     * <p>⚠️ 不是性能调优参数：不多要就会返回一个短页，而短页会被客户端理解成「到底了」。
     */
    private static final int OVERFETCH = 10;

    private final ContentPostRepository posts;
    private final ContentLikeRepository likes;
    private final CommentRepository comments;
    private final ContentTagQueryService contentTags;
    private final ContentSpeciesResolver speciesResolver;
    private final PetProfileRepository pets;
    private final FeedSeenStore seenStore;
    private final FeedSequenceStore sequenceStore;
    private final FeedRankEngine engine;
    private final FeedRankProperties props;

    public FeedRecommendationService(ContentPostRepository posts, ContentLikeRepository likes,
            CommentRepository comments, ContentTagQueryService contentTags,
            ContentSpeciesResolver speciesResolver, PetProfileRepository pets,
            FeedSeenStore seenStore, FeedSequenceStore sequenceStore, FeedRankEngine engine,
            FeedRankProperties props) {
        this.posts = posts;
        this.likes = likes;
        this.comments = comments;
        this.contentTags = contentTags;
        this.speciesResolver = speciesResolver;
        this.pets = pets;
        this.seenStore = seenStore;
        this.sequenceStore = sequenceStore;
        this.engine = engine;
        this.props = props;
    }

    /**
     * 一页推荐序内容（已按推荐序排好）。
     *
     * @param posts      本页内容，顺序即展示顺序
     * @param nextCursor 下一页游标；null = 到底了
     */
    public record RankedPage(List<ContentPost> posts, String nextCursor, boolean hasMore) {
    }

    /**
     * 读一页。
     *
     * @param viewerId      登录用户 id；null = 游客
     * @param anonSessionId 游客的匿名会话 id（登录用户忽略）
     * @param cursor        上一页游标；null = 首屏 / 下拉刷新
     * @param pageSize      每页条数
     * @param yieldId       首屏要让位的顶置内容 id；null = 不让位
     */
    @Transactional(readOnly = true)
    public RankedPage page(Long viewerId, String anonSessionId, String cursor, int pageSize,
            Long yieldId) {
        Instant now = Instant.now();
        FeedRankCacheKey key = FeedRankCacheKey.of(viewerId, anonSessionId);
        boolean firstPage = (cursor == null || cursor.isBlank());
        FeedRankCursor rc = firstPage
                ? new FeedRankCursor(sequenceStore.newSeed(now), 0)
                : FeedRankCursor.decode(cursor);

        List<ContentPost> page = new ArrayList<>();
        Set<Long> served = new LinkedHashSet<>();
        int consumed = rc.consumed();
        boolean sequenceExhausted = false;

        while (page.size() < pageSize && !sequenceExhausted) {
            int want = pageSize - page.size() + OVERFETCH;
            List<Long> slice = slice(key, rc.seed(), consumed, want, viewerId, now);
            if (slice.isEmpty()) {
                sequenceExhausted = true;
                break;
            }
            if (slice.size() < want) {
                sequenceExhausted = true; // 序列到底了；本轮取到的还是要用
            }
            // 🔴 重新套一遍候选池过滤（见类注释）
            Map<Long, ContentPost> alive = posts
                    .findRankableByIds(slice, viewerId != null, viewerId).stream()
                    .collect(Collectors.toMap(ContentPost::getId, x -> x, (a, b) -> a));
            for (Long id : slice) {
                consumed++;
                if (page.size() >= pageSize) {
                    consumed--; // 这条没消费，留给下一页
                    break;
                }
                // 🛡 首屏让位：顶置的那条不在第一页出现（Story 4.2 的口径，整页 20 条）
                if (firstPage && yieldId != null && yieldId.equals(id)) {
                    continue;
                }
                ContentPost p = alive.get(id);
                if (p == null || !served.add(id)) {
                    continue; // 已不合格 / 重复（续算时理论上不会，兜一道）
                }
                page.add(p);
            }
        }

        // 🔴 AC2（16.1）：序列返回给客户端时即记入曝光，不等客户端上报。
        seenStore.markSeen(key, served, now);

        boolean hasMore = !sequenceExhausted || page.size() == pageSize;
        String next = (page.isEmpty() || !hasMore) ? null
                : new FeedRankCursor(rc.seed(), consumed).encode();
        return new RankedPage(List.copyOf(page), next, next != null);
    }

    /**
     * 取序列的一段；不够就生成 / 续算。
     *
     * <p>🛡 <b>Redis 不可用时走的也是这条路</b>（降级链级别 3）：{@code length} 恒 0、
     * {@code read} 恒空、{@code append} 无效 ⇒ 每次请求都实时算一遍并在内存里切片。
     * 代价是翻页可能重复（两次请求之间赞评变了），<b>但绝不返回空页或报错</b>。
     */
    private List<Long> slice(FeedRankCacheKey key, String seed, int offset, int limit,
            Long viewerId, Instant now) {
        List<Long> cached = sequenceStore.read(key, seed, offset, limit);
        if (!cached.isEmpty()) {
            return cached;
        }
        List<Long> full = extend(key, seed, offset + limit, viewerId, now);
        if (offset >= full.size()) {
            return List.of();
        }
        return full.subList(offset, Math.min(offset + limit, full.size()));
    }

    /**
     * 把序列补到至少 {@code target} 条，返回<b>完整</b>序列（含已缓存部分）。
     *
     * <p>续算时把<b>已缓存的 id 从候选池里排掉</b> —— 引擎是确定性的，但候选池会变
     * （新帖、赞评变化），不排掉就可能把已下发过的内容再排一遍。
     */
    private List<Long> extend(FeedRankCacheKey key, String seed, int target, Long viewerId,
            Instant now) {
        long have = sequenceStore.length(key, seed);
        List<Long> stored = have == 0 ? List.of()
                : sequenceStore.read(key, seed, 0, (int) Math.min(have, Integer.MAX_VALUE));
        if (stored.size() >= target) {
            return stored;
        }
        int block = Math.max(props.sequenceLength(), target - stored.size());
        List<Long> fresh = generate(key, viewerId, new HashSet<>(stored), block, now);
        if (!fresh.isEmpty()) {
            sequenceStore.append(key, seed, fresh);
        }
        List<Long> full = new ArrayList<>(stored);
        full.addAll(fresh);
        return full;
    }

    /** 跑一次候选池取数 + 打分配比，产出 {@code wanted} 条 id。 */
    private List<Long> generate(FeedRankCacheKey key, Long viewerId, Set<Long> exclude, int wanted,
            Instant now) {
        List<ContentPost> pool = posts.findRankCandidatePool(viewerId != null, viewerId,
                PageRequest.of(0, props.candidatePoolSize()));
        List<ContentPost> usable = pool.stream().filter(p -> !exclude.contains(p.getId())).toList();
        if (usable.isEmpty()) {
            return List.of();
        }
        List<Long> ids = usable.stream().map(ContentPost::getId).toList();

        // 🛡 三次批量聚合，一次取完整池（AC5：禁止逐条 COUNT）
        Map<Long, Long> likeCounts = likes.countByPostIdIn(ids).stream()
                .collect(Collectors.toMap(ContentLikeRepository.PostLikeCount::getPostId,
                        ContentLikeRepository.PostLikeCount::getLikeCount));
        Map<Long, Long> commentCounts = comments.countVisibleForViewerIn(ids, viewerId).stream()
                .collect(Collectors.toMap(CommentRepository.PostCommentCount::getPostId,
                        CommentRepository.PostCommentCount::getCommentCount));
        Set<Long> honored = contentTags.findVisibleTags(ids, now).keySet();
        Map<Long, ResolvedSpecies> species = speciesResolver.resolveAll(usable.stream()
                .map(p -> new ContentSpeciesResolver.Input(p.getId(), p.getSpeciesOverride(),
                        p.getAuthorId()))
                .toList());

        List<RankCandidate> candidates = new ArrayList<>(usable.size());
        for (ContentPost p : usable) {
            FeedAttribute attr = FeedAttribute.from(p.getType(),
                    p.getVisibility() == ContentVisibility.PUBLIC);
            if (attr == null) {
                continue;
            }
            ResolvedSpecies rs = species.get(p.getId());
            candidates.add(new RankCandidate(p.getId(), p.getAuthorId(), attr,
                    rs == null ? null : rs.species(), p.getCreatedAt(),
                    likeCounts.getOrDefault(p.getId(), 0L),
                    commentCounts.getOrDefault(p.getId(), 0L)));
        }

        RankParams params = params(candidates);
        Map<Long, Double> decay = seenStore.decayFactors(key,
                candidates.stream().map(RankCandidate::id).toList(), now);

        FeedRankEngine.Result r = engine.rank(new FeedRankEngine.Input(candidates,
                viewerMainSpecies(viewerId), decay, honored,
                // 🛡 限流系数：Epic 17 接入前恒 1.0（空 Map 即缺省 1.0）
                Map.of(), now, params), wanted);
        if (r.attributeRelaxed() > 0 || r.speciesRelaxed() > 0) {
            // 🛡 级别 1、2 是**预期行为，不告警** —— 只记 debug 供排查（§6.2）。
            log.debug("推荐序降级 池={} 属性放宽={} 物种放宽={} 防扎堆让步={}",
                    candidates.size(), r.attributeRelaxed(), r.speciesRelaxed(),
                    r.antiClumpOverridden());
        }
        return r.picked().stream().map(RankCandidate::id).toList();
    }

    /**
     * 打分参数。
     *
     * <p>⚠️ {@code P95} <b>由本次候选池现算</b>（候选池本身就是「最近 N 条」，天然是近期口径），
     * 因此不多发一次查询、也不会为空。
     * 🔴 Story 16.4 会把它换成「近 30 天动态重算 + 重算失败沿用上次」并搬进配置中心；
     * 在那之前这里是唯一的取值处，<b>别在别处再算一份</b>。
     *
     * <p>荣誉加成直接取 {@link ContentTagQueryService#RANK_WEIGHT_MULTIPLIER} ——
     * 🛡 那是既有的唯一事实源，本 story 不新写一个 1.3。
     */
    private RankParams params(List<RankCandidate> candidates) {
        RankParams base = RankParams.defaults(percentile95(candidates));
        return new RankParams(base.freshnessWeight(), base.interactionWeight(),
                base.commentWeight(), base.interactionP95(),
                ContentTagQueryService.RANK_WEIGHT_MULTIPLIER,
                base.mainSpeciesQuota(), base.otherSpeciesQuota(), base.generalQuota(),
                base.maxSameAttributeRun(), base.maxSameAuthorRun(),
                base.maxSameAuthorPerWindow(), base.maxSameOtherSpeciesRun());
    }

    /** 候选池互动量的 95 分位。空池 / 全零 → 0（引擎会把互动度按 0 计，不除零）。 */
    private static double percentile95(List<RankCandidate> candidates) {
        if (candidates.isEmpty()) {
            return 0d;
        }
        double[] v = candidates.stream()
                .mapToDouble(c -> c.likes() + 2d * c.comments()).sorted().toArray();
        int idx = (int) Math.floor(0.95 * (v.length - 1));
        return v[idx];
    }

    /**
     * 查看者主物种；无宠物档案 / 游客 → {@code null}（物种维度不生效）。
     *
     * <p>🔴 这是<b>「拿不到物种信号」，不是「按用户类型区别对待」</b>：同一个用户建了档案后
     * 自动获得物种偏好，无需任何分档判定。
     *
     * <p>🔴 <b>多宠账号本 story 不实现细化</b>（当前平台单账号单宠物，该分支不触发）——
     * 细化归 V1.2.0 FR-64。
     */
    private String viewerMainSpecies(Long viewerId) {
        if (viewerId == null) {
            return null;
        }
        return pets.findByOwnerId(viewerId).map(PetProfile::getPetType)
                .map(Enum::name).orElse(null);
    }
}
