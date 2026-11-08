package com.tailtopia.admin.pin.service;

import com.tailtopia.admin.pin.dto.PinDetail;
import com.tailtopia.admin.pin.dto.PinRow;
import com.tailtopia.admin.pin.dto.PinSummary;
import com.tailtopia.admin.pin.dto.PinnableContentRow;
import com.tailtopia.admin.pin.dto.TopicPinPreview;
import com.tailtopia.admin.pin.dto.TopicPinRow;
import com.tailtopia.admin.topic.service.AdminTopicStore;
import com.tailtopia.admin.topic.service.TopicView;
import com.tailtopia.admin.audit.service.AdminAuditService;
import com.tailtopia.content.domain.ContentPin;
import com.tailtopia.content.domain.ContentPost;
import com.tailtopia.content.domain.PinObjectType;
import com.tailtopia.content.repository.ContentPostRepository;
import com.tailtopia.content.service.ContentDisplayability;
import com.tailtopia.content.service.ContentPinService;
import com.tailtopia.shared.error.AppException;
import com.tailtopia.shared.schedule.SchedulePhase;
import com.tailtopia.topic.domain.TopicStatus;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 顶置管理的后台视图与写入（Story 11.1 · AB-10A）。
 *
 * <p>🔴 <b>本类不重新实现任何机制</b>。排期写入、同坑位重叠校验、生效判定、下架/注销联动
 * 全部早在 Story 4.1 就随 {@link ContentPinService} 落地了 ——
 * 当时的注释写得很清楚：「本 story 没有对外接口，交付的是机制，供后台接上来之后直接调用」。
 * 本类就是那个「接上来」。
 *
 * <p>🛡 「内容失效未生效」的判定走 {@link ContentDisplayability} —— 与 App 的 Feed 坑位**同一份**。
 */
@Service
public class AdminContentPinService {

    /** 选择器每页条数。候选集接近全量内容，必须分页。 */

    /** 列表摘要截断长度。 */
    private static final int SUMMARY_MAX = 40;

    /** 列表每页条数（V1.3.0 Story 7.3 · AC2）。 */
    public static final int PAGE_SIZE = 20;

    private final ContentPinService pins;
    private final ContentPostRepository posts;
    private final AdminAuditService audit;
    /** 抽屉里的作者昵称（Story 7.3 · AC3 顶置内容预览）。 */
    private final com.tailtopia.auth.service.AccountQueryService accountQuery;
    private final PinnableContentPicker picker;
    /** 话题页坑位（V1.4.2 Story 6.2）：话题存在与状态校验、G1a 话题列表。 */
    private final AdminTopicStore topics;

    /** 表单坑位参数：首页 Feed（库内值同名）。 */
    public static final String SLOT_PARAM_HOME_FEED = ContentPin.SLOT_HOME_FEED;
    /** 表单坑位参数：话题页（库内值由服务端按 D-6 拼成 {@code TOPIC:{topicId}}，绝不收客户端直传的 {@code TOPIC:…}）。 */
    public static final String SLOT_PARAM_TOPIC_PAGE = "TOPIC_PAGE";

    /** 话题抽屉区块「排期中」最多条数（AC8.1）。 */
    public static final int PREVIEW_PENDING_MAX = 3;

    public AdminContentPinService(ContentPinService pins, ContentPostRepository posts,
            AdminAuditService audit, com.tailtopia.auth.service.AccountQueryService accountQuery,
            PinnableContentPicker picker, AdminTopicStore topics) {
        this.pins = pins;
        this.posts = posts;
        this.audit = audit;
        this.accountQuery = accountQuery;
        this.picker = picker;
        this.topics = topics;
    }

    // ================================================================ 话题页坑位（V1.4.2 Story 6.2）

    /**
     * 表单坑位参数 → 库内坑位值（AC4.3 / AC5，D-6）。
     * {@code HOME_FEED} → {@code HOME_FEED}；{@code TOPIC_PAGE} + 可配置话题（ACTIVE / HIDDEN）→
     * {@link ContentPin#topicSlot}；话题不存在 / 已禁用 → 422 {@code admin.err.pins.topicUnavailable}；
     * 其他任意字符串（含原样提交的 {@code TOPIC:5}、空串）→ 422 {@code admin.err.pins.slotInvalid}。
     */
    @Transactional(readOnly = true)
    public String resolveSlot(String slotParam, Long topicId) {
        if (SLOT_PARAM_HOME_FEED.equals(slotParam)) {
            return ContentPin.SLOT_HOME_FEED;
        }
        if (SLOT_PARAM_TOPIC_PAGE.equals(slotParam)) {
            if (topicId == null) {
                throw topicUnavailable();
            }
            return ContentPin.topicSlot(pinnableTopic(topicId)
                    .orElseThrow(AdminContentPinService::topicUnavailable).id());
        }
        throw AppException.validation("坑位不正确").code("admin.err.pins.slotInvalid");
    }

    /** 库内坑位值 → 话题 id（{@code TOPIC:} + 数字）；首页 Feed / 非法值 → {@code null}。 */
    public static Long topicIdOf(String slot) {
        if (slot == null || !slot.startsWith(ContentPin.SLOT_TOPIC_PREFIX)) {
            return null;
        }
        String rest = slot.substring(ContentPin.SLOT_TOPIC_PREFIX.length());
        if (!rest.matches("\\d{1,18}")) {
            return null;
        }
        return Long.parseLong(rest);
    }

    /** 可配置话题页顶置的话题（ACTIVE / HIDDEN）；不存在或已禁用 → empty（AC5）。 */
    @Transactional(readOnly = true)
    public java.util.Optional<TopicView> pinnableTopic(long topicId) {
        return topics.findById(topicId).filter(t -> t.status() != TopicStatus.DISABLED);
    }

    /** G1b 用：同上，但不可配置 → 404（AC3.3：DISABLED 与不存在同样 404）。 */
    @Transactional(readOnly = true)
    public TopicView requirePinnableTopic(long topicId) {
        return pinnableTopic(topicId).orElseThrow(() -> AppException.notFound("话题不存在")
                .code("admin.err.topics.notFound"));
    }

    /** 某排期所在话题 id（非 htmx 写操作的重定向回 G1b 用）；不存在 / 首页 Feed → {@code null}。 */
    @Transactional(readOnly = true)
    public Long topicIdOfPin(long pinId) {
        return pins.byId(pinId).map(p -> topicIdOf(p.getSlot())).orElse(null);
    }

    /** 一页 G1a 行 + 有无下一页。 */
    public record TopicRowsPage(List<TopicPinRow> rows, boolean hasNext, int page) {
    }

    /**
     * G1a（AC2）：一次查当页话题（ACTIVE / HIDDEN、搜索、热度倒序、20 条）+ 一次查这些坑位的全部排期，
     * Java 内算「当前生效（取 1 条）」与「排期中条数（未结束且未开始）」—— 判定走 {@link #list} 同一份 PinRow。
     */
    @Transactional(readOnly = true)
    public TopicRowsPage topicRows(String q, int page, Instant now) {
        AdminTopicStore.TopicPage found = topics.pinnablePage(q, page);
        List<String> slots = found.rows().stream().map(t -> ContentPin.topicSlot(t.id())).toList();
        Map<String, List<PinRow>> bySlot = toRows(pins.listBySlots(slots), now).stream()
                .collect(Collectors.groupingBy(PinRow::slot));
        List<TopicPinRow> rows = found.rows().stream().map(t -> {
            List<PinRow> mine = bySlot.getOrDefault(ContentPin.topicSlot(t.id()), List.of());
            return new TopicPinRow(t, activeOf(mine), mine.stream().filter(AdminContentPinService::pending).count());
        }).toList();
        return new TopicRowsPage(rows, found.hasNext(), found.page());
    }

    /** 话题抽屉区块（AC8.1）：生效 1 + 排期中最多 {@link #PREVIEW_PENDING_MAX} 条（按开始时间升序）。 */
    @Transactional(readOnly = true)
    public TopicPinPreview topicPinPreview(long topicId, Instant now) {
        List<PinRow> all = list(ContentPin.topicSlot(topicId), now);
        List<PinRow> pending = all.stream().filter(AdminContentPinService::pending)
                .sorted(java.util.Comparator.comparing(PinRow::startsAt)
                        .thenComparing(java.util.Comparator.comparingLong(PinRow::id)))
                .limit(PREVIEW_PENDING_MAX)
                .toList();
        return new TopicPinPreview(activeOf(all), pending);
    }

    /** 生效中：未收手且处于窗口内；重叠校验保证至多一条，仍取开始最晚的一条兜底（同 {@code findActiveOne}）。 */
    private static PinRow activeOf(List<PinRow> rows) {
        return rows.stream().filter(r -> !r.ended() && r.phase() == SchedulePhase.ACTIVE)
                .max(java.util.Comparator.comparing(PinRow::startsAt)
                        .thenComparing(java.util.Comparator.comparingLong(PinRow::id)))
                .orElse(null);
    }

    /** 排期中：未收手（被提前结束的待生效不算）且尚未开始。 */
    private static boolean pending(PinRow r) {
        return !r.ended() && r.phase() == SchedulePhase.PENDING;
    }

    /**
     * 话题坑位写入前，在<b>同一事务</b>里对话题行加 {@code FOR SHARE} 并复核状态（复审 a）：
     * 关掉「resolveSlot 通过 → 运营禁用话题 → 本排期才落库」的窗口 —— 否则禁用时的 {@code terminateForSlot}
     * 看不到这条，话题恢复后它会自己重新生效。DISABLED / 行不存在 → 422 {@code topicUnavailable}。
     * 首页 Feed（{@code topicId == null}）不加锁。
     */
    private void lockPinnableTopic(Long topicId) {
        if (topicId == null) {
            return;
        }
        TopicStatus st = topics.lockStatusForShare(topicId).orElse(null);
        if (st == null || st == TopicStatus.DISABLED) {
            throw topicUnavailable();
        }
    }

    private static AppException topicUnavailable() {
        return AppException.validation("该话题已禁用或不存在，不能配置顶置").code("admin.err.pins.topicUnavailable");
    }

    /** 一页排期 + 有无下一页（列表按生效时间倒序）。 */
    public record PinPage(List<PinRow> rows, boolean hasNext, int page) {
    }

    /**
     * 列表（Story 7.3 · AC1 / AC2）：按状态筛 + 生效时间倒序 + 分页。
     *
     * <p>⚠️ 筛选与分页都在**应用层**做：{@code phase} 由 {@code ScheduleWindow} 判定（含「提前结束」
     * 这种只看 {@code starts_at/ends_at} 判不出来的情况），拿 SQL 再算一遍就有第二份口径。
     * 一个坑位的全部排期是低基数数据，整表读一次不是问题。
     *
     * @param status {@code ACTIVE / PENDING / ENDED}；空或非法值 = 不筛（手改 URL 不该出 500）
     */
    @Transactional(readOnly = true)
    public PinPage page(String slot, String status, int page, Instant now) {
        return page(list(slot, now), status, page);
    }

    /**
     * 同上，但复用调用方已取好的整表 —— 一次请求里列表与摘要条共用一次读，
     * 且两者的 phase 判定用的是**同一个 now**（各自取 now 会在跨秒时给出不一致的状态）。
     */
    public PinPage page(List<PinRow> rows, String status, int page) {
        SchedulePhase want = phaseFilter(status);   // 提到循环外：非法 status 时不必每行构造一次异常
        List<PinRow> all = rows.stream()
                .filter(r -> want == null || matches(r, want))
                .sorted(java.util.Comparator.comparing(PinRow::startsAt).reversed()
                        .thenComparing(java.util.Comparator.comparingLong(PinRow::id).reversed()))
                .toList();
        // ⚠️ 先把页码钳到合法范围再算 offset：`page * PAGE_SIZE` 在 int 域会溢出成负数
        //    （?page=107374183 起），负下标进 subList 就是 500 —— 而手改 URL 不该出 500。
        //    顺带这也是「页码越界回退到最后一页」：处置掉最后一页最后一条后按原页码重拉不会落到空页。
        int lastPage = all.isEmpty() ? 0 : (all.size() - 1) / PAGE_SIZE;
        int safePage = Math.min(Math.max(page, 0), lastPage);
        int from = Math.min(safePage * PAGE_SIZE, all.size());
        int to = Math.min(from + PAGE_SIZE, all.size());
        return new PinPage(all.subList(from, to), to < all.size(), safePage);
    }

    /** 摘要条：生效中 · 待生效 · 已结束（口径与列表状态列同源，见 {@link PinSummary}）。 */
    @Transactional(readOnly = true)
    public PinSummary summary(String slot, Instant now) {
        return summary(list(slot, now));
    }

    /** 同上，复用整表。 */
    public PinSummary summary(List<PinRow> all) {
        // ⚠️ 用 ended() 而不是 phase == ENDED：被手动提前结束的「待生效」排期 phase 仍是 PENDING（见 PinRow.ended）。
        return new PinSummary(
                all.stream().filter(r -> !r.ended() && r.phase() == SchedulePhase.ACTIVE).count(),
                all.stream().filter(r -> !r.ended() && r.phase() == SchedulePhase.PENDING).count(),
                all.stream().filter(PinRow::ended).count());
    }

    /** 抽屉：一条排期 + 坑位内容预览（AC3）；不存在 → 404。 */
    @Transactional(readOnly = true)
    public PinDetail detail(long id, Instant now) {
        ContentPin p = pins.byId(id).orElseThrow(() -> com.tailtopia.shared.error.AppException
                .notFound("顶置排期不存在").code("admin.err.pins.notFound"));
        ContentPost post = p.getContentId() == null ? null
                : posts.findById(p.getContentId()).orElse(null);
        boolean gone = p.getObjectType() != PinObjectType.PROMO
                && !ContentDisplayability.isDisplayable(post);
        String summary = p.getObjectType() == PinObjectType.PROMO ? p.getPromoTitle()
                : (post == null ? null : truncate(post.getText()));
        PinRow row = new PinRow(p.getId(), p.getSlot(), p.getObjectType().name(), p.getContentId(),
                summary, p.getStartsAt(), p.getEndsAt(), p.getTerminatedAt(), pins.phaseOf(p, now), gone);
        var author = post == null || post.getAuthorId() == null ? null
                : accountQuery.findAuthorViews(List.of(post.getAuthorId())).get(post.getAuthorId());
        return new PinDetail(row,
                post == null || post.getImageUrls() == null || post.getImageUrls().isEmpty()
                        ? null : post.getImageUrls().get(0),
                author == null ? null : author.nickname(), author != null && author.deleted(),
                p.getPromoImageUrl(), p.getPromoTitle(), p.getPromoLinkUrl());
    }

    /** 状态匹配：已提前结束的排期一律算「已结束」，与摘要条 / 徽标同口径。 */
    private static boolean matches(PinRow r, SchedulePhase want) {
        return want == SchedulePhase.ENDED ? r.ended() : (!r.ended() && r.phase() == want);
    }

    /** 状态筛选参数 → 阶段；空 / 非法 = 不筛。 */
    private static SchedulePhase phaseFilter(String status) {
        if (status == null || status.isBlank()) {
            return null;
        }
        try {
            return SchedulePhase.valueOf(status);
        } catch (IllegalArgumentException e) {
            return null;
        }
    }

    /** 某坑位的全部排期（含历史），已带 phase 与「内容失效」标记。 */
    @Transactional(readOnly = true)
    public List<PinRow> list(String slot, Instant now) {
        return toRows(pins.listBySlot(slot), now);
    }

    /** 排期 → 带 phase 与「内容失效」标记的行（内容整批一次取）。 */
    private List<PinRow> toRows(List<ContentPin> all, Instant now) {
        List<Long> contentIds = all.stream()
                .map(ContentPin::getContentId)
                .filter(java.util.Objects::nonNull)
                .toList();
        Map<Long, ContentPost> byId = contentIds.isEmpty()
                ? Map.of()
                : posts.findAllById(contentIds).stream()
                        .collect(Collectors.toMap(ContentPost::getId, Function.identity()));

        return all.stream().map(p -> {
            boolean gone = false;
            String summary;
            if (p.getObjectType() == PinObjectType.PROMO) {
                summary = p.getPromoTitle();
            } else {
                ContentPost post = p.getContentId() == null ? null : byId.get(p.getContentId());
                // 🛡 与 Feed 坑位同一判定：缺了这一致性，后台会说「生效中」而 App 上是空的。
                gone = !ContentDisplayability.isDisplayable(post);
                summary = post == null ? null : truncate(post.getText());
            }
            return new PinRow(p.getId(), p.getSlot(), p.getObjectType().name(), p.getContentId(),
                    summary, p.getStartsAt(), p.getEndsAt(), p.getTerminatedAt(),
                    pins.phaseOf(p, now), gone);
        }).toList();
    }

    /** 内容选择器：只返回可公开展示的内容，分页。 */
    @Transactional(readOnly = true)
    public List<PinnableContentRow> pickable(String keyword, int page) {
        return picker.page(keyword, page);
    }

    /** 同上；{@code topicId} 非空 = 只列该话题下的帖子（Story 6.2 · AC4.2）。 */
    @Transactional(readOnly = true)
    public List<PinnableContentRow> pickable(String keyword, int page, Long topicId) {
        return picker.page(keyword, page, topicId);
    }

    /** @return 新排期 id（Story 7.3：抽屉建完要立刻打开它） */
    @Transactional
    public long createContentPin(long adminId, String slot, long contentId,
            Instant startsAt, Instant endsAt) {
        Long topicId = topicIdOf(slot);
        lockPinnableTopic(topicId);
        if (topicId != null) {
            // 🔴 服务端权威（Story 6.2 · AC4.3）：话题页内容顶置的帖子必须挂在该话题下且可公开展示、
            //    作者未注销且 ACTIVE（isInTopic 内含，复审 b，同 4.2 话题列表口径），
            //    否则 App 话题页会出现「点进去是别的话题的帖 / 空的置顶」。推广卡片不受话题限制。
            boolean ok = posts.findById(contentId)
                    .filter(PinnableContentPicker::pinnable)
                    .filter(p -> posts.isInTopic(p.getId(), topicId))
                    .isPresent();
            if (!ok) {
                throw AppException.validation("所选内容不属于该话题，或已不可公开展示")
                        .code("admin.err.pins.notInTopic");
            }
        }
        ContentPin saved = pins.schedule(ContentPin.ofContent(slot, contentId, startsAt, endsAt));
        audit.record(adminId, "CONTENT_PIN_CREATE", "content_pin", String.valueOf(saved.getId()),
                "slot=" + slot + " contentId=" + contentId);
        return saved.getId();
    }

    /** @return 新排期 id（同上） */
    @Transactional
    public long createPromoPin(long adminId, String slot, String imageUrl, String title,
            String linkUrl, Instant startsAt, Instant endsAt) {
        lockPinnableTopic(topicIdOf(slot));
        ContentPin saved = pins.schedule(
                ContentPin.ofPromo(slot, imageUrl, title, linkUrl, startsAt, endsAt));
        audit.record(adminId, "CONTENT_PIN_CREATE", "content_pin", String.valueOf(saved.getId()),
                "slot=" + slot + " promo=" + title);
        return saved.getId();
    }

    @Transactional
    public void reschedule(long adminId, long id, Instant startsAt, Instant endsAt) {
        pins.update(id, startsAt, endsAt, null);
        audit.record(adminId, "CONTENT_PIN_EDIT", "content_pin", String.valueOf(id),
                "startsAt=" + startsAt + " endsAt=" + endsAt);
    }

    /** 手动提前结束。已结束的为幂等 no-op。 */
    @Transactional
    public boolean terminate(long adminId, long id, Instant at) {
        boolean changed = pins.terminateNow(id, at);
        if (changed) {
            audit.record(adminId, "CONTENT_PIN_TERMINATE", "content_pin", String.valueOf(id),
                    "at=" + at);
        }
        return changed;
    }

    private static String truncate(String text) {
        if (text == null) {
            return null;
        }
        String t = text.strip();
        return t.length() <= SUMMARY_MAX ? t : t.substring(0, SUMMARY_MAX) + "…";
    }
}
