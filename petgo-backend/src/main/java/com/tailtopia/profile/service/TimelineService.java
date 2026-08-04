package com.tailtopia.profile.service;

import com.tailtopia.content.service.ContentService;
import com.tailtopia.content.service.GrowthMomentView;
import com.tailtopia.profile.domain.HealthRecord;
import com.tailtopia.profile.domain.PetProfile;
import com.tailtopia.profile.dto.ArchiveStatsResponse;
import com.tailtopia.profile.dto.CalendarMonthResponse;
import com.tailtopia.profile.dto.DayDetailResponse;
import com.tailtopia.profile.dto.TimelineItemResponse;
import com.tailtopia.profile.dto.TimelinePageResponse;
import com.tailtopia.profile.domain.IdCard;
import com.tailtopia.profile.repository.HealthRecordRepository;
import com.tailtopia.profile.repository.IdCardRepository;
import com.tailtopia.profile.repository.MilestoneCompletionRepository;
import com.tailtopia.profile.service.HealthEventTimelineSource.HealthEventView;
import com.tailtopia.shared.error.AppException;
import java.time.Instant;
import java.time.LocalDate;
import java.time.YearMonth;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.TreeMap;
import org.springframework.data.domain.PageRequest;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 成长时间线聚合（Story 2.4 · B1）。合并「快乐时刻」（content service）与「健康事件」
 * （{@link HealthEventTimelineSource}，2.5 实现）按 createdAt 倒序游标分页。
 *
 * <p>模块边界：经 service 接口取数，**不直接 join content_posts / 健康事件表**。
 * 健康源 2.4 期无 bean → {@link ObjectProvider#getIfAvailable()} 返回 null，聚合对空源稳健。
 */
@Service
public class TimelineService {

    private static final Logger log = LoggerFactory.getLogger(TimelineService.class);

    private static final int MAX_LIMIT = 50;

    /** 同日不跨页时的取数上限（防病态数据无限放大；触顶会 warn，不静默截断）。 */
    private static final int MAX_FETCH = 500;

    /**
     * 时间线**取数/游标序**（AD-1）：事件日期倒序 → 同日发布/完成时刻倒序。
     *
     * <p>⚠️ 这是**内部序**，不等于对外展示序。FR-82 / Story 3.2 AC5 要求「整体按事件日期倒序、
     * **同日按发布或完成时间正序**」，实现方式是**页内重排**（见 {@link #withinDayAscending}）而非改本比较器：
     * 游标语义依赖「同日倒序 + 严格小于锚点」，若把比较器改成同日正序，取下一页时会把该日较晚的条目
     * 再取一遍（重复）。同日条目不跨页（Rule 3）保证了页内重排绝对安全。
     */
    private static final Comparator<TimelineItemResponse> TIMELINE_ORDER =
            Comparator.comparing(TimelineItemResponse::effectiveDate)
                    .thenComparing(TimelineItemResponse::date)
                    .reversed();

    private final ProfileService profileService;
    private final ContentService contentService;
    private final ObjectProvider<HealthEventTimelineSource> healthSource;
    private final MilestoneService milestoneService;
    private final HealthRecordRepository healthRecords;
    private final MilestoneCompletionRepository milestoneCompletions;
    private final IdCardRepository idCards;

    public TimelineService(ProfileService profileService, ContentService contentService,
            ObjectProvider<HealthEventTimelineSource> healthSource,
            MilestoneService milestoneService, HealthRecordRepository healthRecords,
            MilestoneCompletionRepository milestoneCompletions, IdCardRepository idCards) {
        this.profileService = profileService;
        this.contentService = contentService;
        this.healthSource = healthSource;
        this.milestoneService = milestoneService;
        this.healthRecords = healthRecords;
        this.milestoneCompletions = milestoneCompletions;
        this.idCards = idCards;
    }

    /**
     * 成长时间线分页（Story 3.1 重构 · AD-1）。
     *
     * <p><b>重构前的现网缺陷</b>：游标用 {@code createdAt} 取数、排序却用 {@code effectiveDate}
     * （快乐时刻取 {@code eventDate}）——两把尺子。补记一篇旧日期的日记（{@code eventDate} 旧、
     * {@code createdAt} 新）在排序上落到旧位置、在翻页上被当作新记录，跨页时丢失或重复。
     *
     * <p><b>重构后</b>：排序键与游标键统一为 {@link TimelineAnchor}（事件日期 + 同日排序键）。
     * 流程严格为 <b>各源按同一锚点取数 → 归并排序 → 统一截断</b>（AD-1 Rule 2，禁止各源先截断再归并），
     * 且<b>同日条目不跨页拆分</b>（Rule 3，必要时该页超出默认页大小）。
     */
    @Transactional(readOnly = true)
    public TimelinePageResponse getTimeline(long ownerId, String cursor, int limit) {
        // 需有档案；无则 404（前端据此渲染空态）。取 petId 供成长帖按当前宠物过滤（bug 271）。
        PetProfile profile = requireProfile(ownerId);
        int pageSize = Math.min(Math.max(limit, 1), MAX_LIMIT);
        TimelineAnchor anchor = TimelineAnchor.decode(cursor);

        // 同日不跨页：若某日条目多到一批取不全，逐级放大批次重取（真实数据下单日极少超过个位数）。
        int fetch = pageSize + 1;
        Batch batch;
        Cut cut;
        while (true) {
            batch = fetchMerged(ownerId, profile.getId(), anchor, fetch);
            cut = cutOnDayBoundary(batch, pageSize);
            if (cut.complete() || fetch >= MAX_FETCH) {
                break;
            }
            fetch = Math.min(fetch * 4, MAX_FETCH);
        }
        if (!cut.complete()) {
            // 兜底（不静默）：单日条目超过 MAX_FETCH，本页在日内截断，下一页从同日继续，不丢不重。
            log.warn("时间线单日条目数超过取数上限，本页在日内截断 ownerId(agent) pageSize={} cap={}",
                    pageSize, MAX_FETCH);
            List<TimelineItemResponse> items = batch.items();
            int end = Math.min(items.size(), pageSize);
            cut = new Cut(List.copyOf(items.subList(0, end)), items.size() > end, true);
        }

        List<TimelineItemResponse> page = cut.page();
        // 游标取**内部序**（同日倒序）的末条：它是本页最后一天里最早的一条，
        // 「严格小于它」正好跳过整个已输出的日期区间（重排后再取会重复，见 withinDayAscending）。
        String nextCursor = null;
        if (cut.hasMore() && !page.isEmpty()) {
            TimelineItemResponse last = page.get(page.size() - 1);
            nextCursor = new TimelineAnchor(last.effectiveDate(), last.date()).encode();
        }
        return new TimelinePageResponse(withinDayAscending(page), nextCursor, cut.hasMore());
    }

    /** 一次截断的结果。{@code complete=false} 表示本批未能把边界所在的那一天取全，需放大批次重取。 */
    private record Cut(List<TimelineItemResponse> page, boolean hasMore, boolean complete) {
    }

    /**
     * 一批取数的结果。{@code allKnown=false} 表示至少有一个源取满了本批上限——它在更早的位置可能还有条目，
     * 因此只有比 {@code trustedFloor} 更新的那段才是**完整已知**的。
     */
    private record Batch(List<TimelineItemResponse> items, boolean allKnown, TimelineAnchor trustedFloor) {
    }

    /**
     * 各源按同一锚点取数后归并排序（AD-1 Rule 2 的前半段：**不在源内截断到页大小**）。
     *
     * <p>健康事件无独立 event_date，其有效日期由 {@code createdAt} 推导、与之单调同序，
     * 故锚点在该源上退化为夹紧后的单键上界（见 {@link TimelineAnchor#createdAtUpperBound()}）。
     *
     * <p><b>可信前缀</b>：若某源恰好返回了 {@code fetch} 条，说明它可能还有更早的条目没取回来。
     * 此时以「取满的源中**最新的那条末尾**」为地板——只有比该地板更新的条目，才能断言本批已是完整集合。
     * 不做这层区分，会把「批次边界」误判成「当天结束」，同日条目仍会被拆到两页（AC3 破）。
     */
    private Batch fetchMerged(long ownerId, long petId, TimelineAnchor anchor, int fetch) {
        boolean allKnown = true;
        TimelineAnchor floor = null;

        // ===== 源① Diary 内容（类①/② 候选：还不知道自己是普通照片卡还是带徽章的） =====
        List<TimelineClassifier.ContentCandidate> contents = new ArrayList<>();
        List<GrowthMomentView> growth = contentService.findGrowthMomentsBeforeAnchor(
                ownerId, petId, anchor.eventDate(), anchor.sameDayKey(), anchor.createdAtUpperBound(), fetch);
        for (GrowthMomentView g : growth) {
            contents.add(new TimelineClassifier.ContentCandidate(
                    g.id(), g.createdAt(), g.eventDate(), g.imageUrls(), g.text()));
        }
        if (growth.size() >= fetch) {
            allKnown = false;
            GrowthMomentView last = growth.get(growth.size() - 1);
            LocalDate eff = last.eventDate() != null
                    ? last.eventDate()
                    : last.createdAt().atZone(ZoneOffset.UTC).toLocalDate();
            floor = newestFloor(floor, new TimelineAnchor(eff, last.createdAt()));
        }

        // ===== 源② 问诊存档（类④，V1.0.0 既有源）=====
        List<TimelineItemResponse> healthItems = new ArrayList<>();
        HealthEventTimelineSource health = healthSource.getIfAvailable();
        if (health != null) {
            List<HealthEventView> events = health.recentHealthEvents(ownerId, anchor.createdAtUpperBound(), fetch);
            for (HealthEventView h : events) {
                healthItems.add(TimelineItemResponse.healthEvent(
                        h.createdAt(), h.aiLevel(), h.symptomSummary(), h.sourceType(), h.sourceRef()));
            }
            if (events.size() >= fetch) {
                allKnown = false;
                HealthEventView last = events.get(events.size() - 1);
                floor = newestFloor(floor, new TimelineAnchor(
                        last.createdAt().atZone(ZoneOffset.UTC).toLocalDate(), last.createdAt()));
            }
        }

        // ===== 源③ 结构化健康记录（类④，Story 3.2 新增；只读镜像，不提供编辑入口）=====
        List<HealthRecord> records = healthRecords.findBeforeAnchor(
                petId, anchor.eventDate(), anchor.sameDayKey(), PageRequest.ofSize(fetch));
        for (HealthRecord r : records) {
            healthItems.add(TimelineItemResponse.healthRecord(r.getId(), r.getCreatedAt(),
                    r.getEventDate(), r.getType() == null ? null : r.getType().name(), r.getNote()));
        }
        if (records.size() >= fetch) {
            allKnown = false;
            HealthRecord last = records.get(records.size() - 1);
            floor = newestFloor(floor, new TimelineAnchor(last.getEventDate(), last.getCreatedAt()));
        }

        // ===== 源④ 里程碑完成（类②/③，Story 3.2 新增；经域内只读视图取数）=====
        List<MilestoneTimelineView> milestones = milestoneCompletions.findTimelineViewsBefore(
                petId, anchor.createdAtUpperBound(), PageRequest.ofSize(fetch));
        if (milestones.size() >= fetch) {
            allKnown = false;
            MilestoneTimelineView last = milestones.get(milestones.size() - 1);
            floor = newestFloor(floor, new TimelineAnchor(
                    last.completedAt().atZone(ZoneOffset.UTC).toLocalDate(), last.completedAt()));
        }

        // ===== 源⑤ 身份证首次生成（类⑤，Story 3.2 新增）=====
        // 每用户卡片数量极少（V1 单宠物），全量取后在内存里挑「最早一张」作为首次生成事件，
        // 无需为它加一条专用查询；锚点过滤同样在内存完成。
        List<TimelineItemResponse> idCardIssues = new ArrayList<>();
        List<IdCard> cards = idCards.findByUserIdOrderByCreatedAtDesc(ownerId);
        if (!cards.isEmpty()) {
            IdCard first = cards.get(cards.size() - 1); // 倒序列表的末尾 = 最早生成
            Instant issuedAt = first.getCreatedAt();
            if (issuedAt != null && issuedAt.isBefore(anchor.createdAtUpperBound())) {
                idCardIssues.add(TimelineItemResponse.idCardIssued(issuedAt,
                        first.getCardNo() == null || first.getCardNo().isBlank()
                                ? null : first.getCardNo()));
            }
        }

        // ===== 五步优先级实时分类（不落库；见 TimelineClassifier 的安全说明）=====
        // 分类要用「当天有无健康条目」这一当天视角的事实，因此必须在**归并前**、拿到本批各源全量后做。
        // 未进入可信前缀的那几天可能分类不完整，但它们同样不会进入本页（见 cutOnDayBoundary），不影响正确性。
        List<TimelineItemResponse> merged =
                new ArrayList<>(TimelineClassifier.classify(contents, healthItems, milestones, idCardIssues));

        merged.sort(TIMELINE_ORDER);
        return new Batch(merged, allKnown, floor);
    }

    /**
     * 页内重排：日期倒序不变，**同日内改为发布/完成时间正序**（AC5）。
     *
     * <p>为什么放在最后做：游标与截断都依赖「同日倒序」的内部序（见 {@link #TIMELINE_ORDER}），
     * 而同日条目不跨页（AD-1 Rule 3）意味着重排只发生在页内、绝不影响页边界与游标。
     * ⚠️ 因此 {@code nextCursor} 必须在重排**之前**用内部序的末条算出（重排后末条是该日最早的一条，
     * 拿它当锚点会把该日较晚的条目再取一遍）。
     */
    private static List<TimelineItemResponse> withinDayAscending(List<TimelineItemResponse> page) {
        Map<LocalDate, List<TimelineItemResponse>> byDay = new LinkedHashMap<>();
        for (TimelineItemResponse item : page) {
            byDay.computeIfAbsent(item.effectiveDate(), d -> new ArrayList<>()).add(item);
        }
        List<TimelineItemResponse> out = new ArrayList<>(page.size());
        for (List<TimelineItemResponse> sameDay : byDay.values()) {
            List<TimelineItemResponse> asc = new ArrayList<>(sameDay);
            asc.sort(Comparator.comparing(TimelineItemResponse::date));
            out.addAll(asc);
        }
        return List.copyOf(out);
    }

    private static TimelineAnchor anchorOf(TimelineItemResponse item) {
        return new TimelineAnchor(item.effectiveDate(), item.date());
    }

    /** 取两个地板中**较新**的那个（更严格）。 */
    private static TimelineAnchor newestFloor(TimelineAnchor a, TimelineAnchor b) {
        if (a == null) {
            return b;
        }
        int cmp = a.eventDate().compareTo(b.eventDate());
        if (cmp != 0) {
            return cmp > 0 ? a : b;
        }
        return a.sameDayKey().isAfter(b.sameDayKey()) ? a : b;
    }

    /** 条目是否严格新于地板（地板为 null = 全部可信）。 */
    private static boolean newerThanFloor(TimelineItemResponse item, TimelineAnchor floor) {
        if (floor == null) {
            return true;
        }
        int cmp = item.effectiveDate().compareTo(floor.eventDate());
        if (cmp != 0) {
            return cmp > 0;
        }
        return item.date().isAfter(floor.sameDayKey());
    }

    /**
     * 归并后统一截断（AD-1 Rule 2 后半段 + Rule 3）：页边界必须落在日期分界上。
     *
     * <p>只在**完整已知**的前缀内做判定（见 {@link Batch}）。若边界所在的那一天在可信前缀里
     * 没有出现「更早的一天」作为收尾标志，就不能断言这天已取全——返回 {@code complete=false}
     * 让调用方放大批次重取。若判定为同日跨界，则**把这一天整体纳入本页**（允许超出默认页大小）：
     * 宁可某页略大，也不能让同日条目被拆到两页后因排序键相同而重复或丢失。
     */
    private static Cut cutOnDayBoundary(Batch batch, int pageSize) {
        List<TimelineItemResponse> items = batch.items();
        // 可信前缀长度：批次全量已知则为全长，否则只算严格新于地板的那段。
        int trusted = batch.allKnown() ? items.size() : 0;
        if (!batch.allKnown()) {
            while (trusted < items.size() && newerThanFloor(items.get(trusted), batch.trustedFloor())) {
                trusted++;
            }
        }

        if (batch.allKnown() && items.size() <= pageSize) {
            return new Cut(List.copyOf(items), false, true);
        }
        if (trusted <= pageSize) {
            return new Cut(List.of(), true, false); // 可信段不足一页，放大批次
        }
        LocalDate lastDay = items.get(pageSize - 1).effectiveDate();
        if (!items.get(pageSize).effectiveDate().equals(lastDay)) {
            return new Cut(List.copyOf(items.subList(0, pageSize)), true, true); // 边界恰好落在日界
        }
        for (int i = pageSize + 1; i < trusted; i++) {
            if (!items.get(i).effectiveDate().equals(lastDay)) {
                return new Cut(List.copyOf(items.subList(0, i)), true, true); // 延伸到该日末尾
            }
        }
        if (batch.allKnown()) {
            return new Cut(List.copyOf(items), false, true); // 剩下全是同一天，且已知无更多
        }
        return new Cut(List.of(), true, false); // 该日在可信段内未见收尾，放大批次
    }

    /**
     * 日历月视图（Story 2.4 R2 · F9）：按 {@code event_date} 聚合当月有记录日。
     * 每日首图取该 event_date 下**最早 created_at** 快乐时刻首图；并入当日健康事件 🏥 角标。
     */
    @Transactional(readOnly = true)
    public CalendarMonthResponse getCalendarMonth(long ownerId, int year, int month) {
        PetProfile profile = requireProfile(ownerId);
        YearMonth ym = YearMonth.of(year, month);
        LocalDate from = ym.atDay(1);
        LocalDate to = ym.atEndOfMonth();

        // day -> cell（TreeMap 保证 day 升序）。
        Map<Integer, CalendarMonthResponse.DayCell> byDay = new TreeMap<>();
        // 快乐时刻已按 event_date 升、created_at 升排序 → 每日首次出现即最早 created_at。
        for (GrowthMomentView g : contentService.findGrowthMomentsInMonth(ownerId, profile.getId(), from, to)) {
            if (g.eventDate() == null) {
                continue; // 防御：非 GROWTH_MOMENT 不应出现，但 eventDate 必非空
            }
            int day = g.eventDate().getDayOfMonth();
            CalendarMonthResponse.DayCell existing = byDay.get(day);
            if (existing == null) {
                byDay.put(day,
                        new CalendarMonthResponse.DayCell(day, g.firstImageUrl(), true, false, null, 0));
            }
            // 同日后续记录不覆盖首图（已是最早 created_at）。
        }

        HealthEventTimelineSource health = healthSource.getIfAvailable();
        if (health != null) {
            Instant rangeFrom = from.atStartOfDay(ZoneOffset.UTC).toInstant();
            Instant rangeTo = to.plusDays(1).atStartOfDay(ZoneOffset.UTC).toInstant();
            for (HealthEventView h : health.healthEventsInRange(ownerId, rangeFrom, rangeTo)) {
                int day = h.createdAt().atZone(ZoneOffset.UTC).toLocalDate().getDayOfMonth();
                CalendarMonthResponse.DayCell c = byDay.get(day);
                if (c == null) {
                    byDay.put(day,
                            new CalendarMonthResponse.DayCell(day, null, false, true, null, 0));
                } else if (!c.hasHealthEvent()) {
                    byDay.put(day, new CalendarMonthResponse.DayCell(day, c.firstImageUrl(),
                            c.hasHappyMoment(), true, c.healthRecordType(), c.healthRecordCount()));
                }
            }
        }

        // 健康记录（疫苗/驱虫/绝育…）按 event_date 并入分类角标（bug 20260722-352）。
        // 只补分类类型，不覆盖已有 diary 图 / 问诊态；渲染优先级（图>问诊>健康记录）交前端。
        for (HealthRecord r : healthRecords.findByPetProfileIdAndEventDateBetweenOrderByEventDateAscIdAsc(
                profile.getId(), from, to)) {
            if (r.getEventDate() == null) {
                continue;
            }
            int day = r.getEventDate().getDayOfMonth();
            CalendarMonthResponse.DayCell c = byDay.get(day);
            String type = r.getType() == null ? null : r.getType().name();
            if (c == null) {
                byDay.put(day, new CalendarMonthResponse.DayCell(day, null, false, false, type, 1));
            } else {
                // 首条决定 type（保持现状），条数累计 —— Story 3.4 只加这一维，不重建已有五维。
                byDay.put(day, new CalendarMonthResponse.DayCell(day, c.firstImageUrl(),
                        c.hasHappyMoment(), c.hasHealthEvent(),
                        c.healthRecordType() == null ? type : c.healthRecordType(),
                        c.healthRecordCount() + 1));
            }
        }

        return new CalendarMonthResponse(year, month, List.copyOf(byDay.values()));
    }

    /**
     * 当天详情（Story 2.4 R2 · F9 · Story 3.4 扩为三源）：某 event_date 当天的
     * 快乐时刻 + 问诊健康事件 + **结构化健康记录**。
     *
     * <p>排序（AD-10 / UX-DR12，覆盖 FR-37 原「按发布时间正序」）：**先按大类** diary &gt; 问诊 &gt;
     * 健康记录，**类内**再按时间正序。⚠️ 先补齐第三个数据源再改排序 —— 只改排序会漏掉整整一类条目。
     */
    @Transactional(readOnly = true)
    public DayDetailResponse getDayDetail(long ownerId, LocalDate date) {
        PetProfile profile = requireProfile(ownerId);
        List<TimelineItemResponse> items = new ArrayList<>();
        for (GrowthMomentView g : contentService.findGrowthMomentsOnDate(ownerId, profile.getId(), date)) {
            items.add(TimelineItemResponse.happyMoment(
                    g.id(), g.createdAt(), g.eventDate(), g.imageUrls(), g.text()));
        }
        HealthEventTimelineSource health = healthSource.getIfAvailable();
        if (health != null) {
            Instant dayStart = date.atStartOfDay(ZoneOffset.UTC).toInstant();
            Instant dayEnd = date.plusDays(1).atStartOfDay(ZoneOffset.UTC).toInstant();
            for (HealthEventView h : health.healthEventsOnDay(ownerId, dayStart, dayEnd)) {
                items.add(TimelineItemResponse.healthEvent(h.createdAt(), h.aiLevel(), h.symptomSummary(), h.sourceType(), h.sourceRef()));
            }
        }
        // 结构化健康记录（第三源，Story 3.4）：按 event_date 取当天。
        for (HealthRecord r : healthRecords.findByPetProfileIdAndEventDateBetweenOrderByEventDateAscIdAsc(
                profile.getId(), date, date)) {
            items.add(TimelineItemResponse.healthRecord(r.getId(), r.getCreatedAt(), r.getEventDate(),
                    r.getType() == null ? null : r.getType().name(), r.getNote()));
        }
        // 大类优先级：diary(0) > 问诊(1) > 健康记录(2)；类内按时间正序。
        items.sort(Comparator.comparingInt(TimelineService::dayDetailCategory)
                .thenComparing(TimelineItemResponse::date));
        return new DayDetailResponse(date, List.copyOf(items));
    }

    /**
     * 档案统计栏（Story 2.4 AC5 · 8.2 连带 AC5）：快乐时刻数 + 问诊数 + 里程碑真进度
     * （已完成 / 总数，接 8.1 roster + completions 真计数）。
     */
    @Transactional(readOnly = true)
    public ArchiveStatsResponse getStats(long ownerId) {
        PetProfile profile = requireProfile(ownerId);
        long happy = contentService.countGrowthMoments(ownerId, profile.getId());
        HealthEventTimelineSource health = healthSource.getIfAvailable();
        long consult = health == null ? 0L : health.countHealthEvents(ownerId);
        MilestoneService.MilestoneProgress progress =
                milestoneService.getProgress(profile.getId(), profile.getPetType());
        long healthRecordCount = healthRecords.countByPetProfileId(profile.getId());
        return new ArchiveStatsResponse(happy, consult, progress.completed(), progress.total(),
                healthRecordCount);
    }

    /** 当天详情的大类序号（AD-10）：diary=0 &gt; 问诊=1 &gt; 结构化健康记录=2。 */
    private static int dayDetailCategory(TimelineItemResponse item) {
        if (TimelineItemResponse.HEALTH_EVENT.equals(item.kind())) {
            return 1;
        }
        if (TimelineItemResponse.HEALTH_RECORD.equals(item.kind())) {
            return 2;
        }
        return 0; // 快乐时刻（含类②）
    }

    private PetProfile requireProfile(long ownerId) {
        return profileService.findByOwnerId(ownerId)
                .orElseThrow(() -> AppException.notFound("尚未创建宠物档案"));
    }

}
