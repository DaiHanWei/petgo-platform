package com.tailtopia.content.service;

import com.tailtopia.content.domain.ContentPost;
import com.tailtopia.content.domain.ContentTag;
import com.tailtopia.content.domain.ContentTagAssignment;
import com.tailtopia.content.dto.ContentTagView;
import com.tailtopia.content.repository.ContentPostRepository;
import com.tailtopia.content.repository.ContentTagAssignmentRepository;
import com.tailtopia.shared.error.AppException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 内容装饰标签的取数与打标（V1.1.6 Story 5.2 · FR-75 / AD-10 / AD-11）。
 *
 * <h2>⚠️ ×1.3 加权在本版本没有可施加的地方</h2>
 * AD-10 Rule 6：标签生效中时该内容在推荐排序上获得 ×1.3 加权 —— **打标是流量动作、不只是发奖动作**。
 *
 * <p>但**本版本首页是纯时间倒序 + 游标分页，没有任何排序算法**
 * （{@code FeedService} 的注释原文就是「无算法、无关注、无缓存」）。没有分数，就没有可以乘 1.3 的东西。
 *
 * <p>因此这里只把**口径与倍数**落在代码里（{@link #RANK_WEIGHT_MULTIPLIER}），
 * 并注明它该在哪一层施加。<b>不假装它已经生效</b> —— 假装的代价是：
 * 以后做排序的人以为已经接好了，这条口径会永远悬空。
 *
 * <p>✅ AC 说的「标签到期失效时加成一并消失」**自动成立**：加权由同一份查询时判定推导，
 * 没有状态列可漂移。
 */
@Service
public class ContentTagQueryService {

    /**
     * 生效中的装饰标签给推荐排序的加权倍数（AD-10 Rule 6，口径以算法文档为准）。
     *
     * <p>⚠️ **本版本无施加处**（首页无排序算法）。推荐排序落地后，应在**算分那一层**
     * 对"当前有生效装饰标签"的内容乘以本倍数；判定直接用 {@link #findVisibleTags}，
     * 不要另建状态列 —— 那会让"到期后加成还在"变成可能。
     */
    public static final double RANK_WEIGHT_MULTIPLIER = 1.3;

    private final ContentTagAssignmentRepository assignments;
    private final ContentPostRepository posts;
    private final com.tailtopia.content.repository.ContentTagRepository tags;

    public ContentTagQueryService(ContentTagAssignmentRepository assignments,
            ContentPostRepository posts,
            com.tailtopia.content.repository.ContentTagRepository tags) {
        this.assignments = assignments;
        this.posts = posts;
        this.tags = tags;
    }

    /** 取消打标（Story 11.2）。不存在视为幂等 no-op。 */
    @Transactional
    public boolean unassign(long assignmentId) {
        return assignments.findById(assignmentId).map(a -> {
            assignments.delete(a);
            return true;
        }).orElse(false);
    }

    /**
     * 一批内容各自当前生效中的装饰标签。
     *
     * <p>空集合直接短路，不发这次查询。
     */
    @Transactional(readOnly = true)
    public Map<Long, List<ContentTagView>> findVisibleTags(Collection<Long> postIds, Instant now) {
        if (postIds == null || postIds.isEmpty()) {
            return Map.of();
        }
        Map<Long, List<ContentTagView>> byPost = new HashMap<>();
        for (Object[] row : assignments.findActiveWithTag(postIds, now)) {
            ContentTagAssignment a = (ContentTagAssignment) row[0];
            ContentTag t = (ContentTag) row[1];
            byPost.computeIfAbsent(a.getPostId(), k -> new ArrayList<>())
                    .add(new ContentTagView(t.getCode(), t.getName(), t.getIcon(), t.getDescription()));
        }
        return byPost;
    }

    /**
     * 给一条内容打标。
     *
     * <h2>🛡 仅公开内容可打标</h2>
     * AC 的原话是"后台对未同步的私密 Diary 不展示打标入口"，但**后台入口本轮不做** ——
     * 只依赖"后台不展示"，这条 AC 在本轮等于没有实现，以后接后台时也无人兜底。
     * 所以校验落在这里：判定与"内容对外可见"同口径（公开 + 已发布）。
     */
    @Transactional
    public ContentTagAssignment assign(long postId, long tagId, Instant startsAt, Instant endsAt) {
        ContentPost post = posts.findById(postId)
                .orElseThrow(() -> AppException.validation("内容不存在"));
        // 🛡 已下线的标签不可再分配（Story 11.2）。已分配的不受影响 —— 下线是"不再发新的"。
        ContentTag tag = tags.findById(tagId)
                .orElseThrow(() -> AppException.validation("标签不存在"));
        if (tag.isRetired()) {
            throw AppException.validation("该标签已下线，不能再分配");
        }
        if (!isPubliclyVisible(post)) {
            throw AppException.validation("只有公开内容可以打标（未同步的私密 Diary 不可打标）");
        }
        if (startsAt == null || (endsAt != null && !endsAt.isAfter(startsAt))) {
            throw AppException.validation("结束时间必须晚于开始时间");
        }
        return assignments.save(ContentTagAssignment.of(postId, tagId, startsAt, endsAt));
    }

    /**
     * 委托给 {@link ContentDisplayability} —— 那是全仓**唯一**的「内容还能不能对外展示」判定
     * （Story 11.1 从 FeedService 抽出）。
     *
     * <p>⚠️ 本方法原先是这里的第三份同款实现（Feed 一份、这里一份、后台顶置列表差点再写一份）。
     * 各写一遍的后果不是重复代码，而是**口径漂移**：某天有人给其中一处加了条件，
     * 另外两处照旧 —— 表现是"同一条内容在 Feed 里不见了、却还能被打标"，无人会立刻发现。
     */
    private static boolean isPubliclyVisible(ContentPost post) {
        return ContentDisplayability.isDisplayable(post);
    }
}
