package com.tailtopia.profile.visitor;

import com.tailtopia.auth.service.AccountQueryService;
import com.tailtopia.content.service.ContentService;
import com.tailtopia.content.service.GrowthMomentView;
import com.tailtopia.profile.domain.MilestoneCatalog;
import com.tailtopia.profile.domain.PetProfile;
import com.tailtopia.profile.dto.ArchiveStatsResponse;
import com.tailtopia.profile.dto.MilestoneItemResponse;
import com.tailtopia.profile.dto.TimelineItemType;
import com.tailtopia.profile.service.MilestoneService;
import com.tailtopia.profile.service.ProfileService;
import com.tailtopia.profile.service.TimelineService;
import com.tailtopia.shared.media.AliyunOssClient;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 访客只读投影层（V1.1.6 Story 2.1 · 架构 AD-1）。
 *
 * <p><b>这是访客数据的唯一出口</b>：H5 分享页直接调用它，App 内访客视图在它之上套一层薄 HTTP 控制器。
 * 两个出口共用同一层，那条「健康记录与问诊记录不下发」的安全规则就只需要正确一次。
 *
 * <h2>🛡 为什么它是一个新类，而不是给作者态查询加一个「访问者」参数</h2>
 * 加参数那条路一旦默认值写错、或某个调用方漏传，<b>泄露是静默的</b> ——
 * 没有任何测试会红，直到有人发现自己的健康记录出现在了别人手机上。
 * 两条路径物理分开，错了就是编译不过或查不到数据。
 *
 * <h2>🛡 依赖清单就是这一层的安全边界</h2>
 * 本类<b>刻意不持有</b> {@code HealthRecordRepository} / {@code HealthEventTimelineSource} /
 * 任何问诊仓库 —— 沿用「分类器不持有 Repository、结构上就不可能写库」的同一手法：
 * <b>让泄露成为不可能，而不是依赖每个改代码的人都记得。</b>
 * {@code VisitorProjectionFieldsTest} 用反射盯着这份清单，谁加了立刻红。
 *
 * <p>作为对照：作者态的 {@link TimelineService} 持有健康记录仓库与健康事件源 ——
 * 那正是本层不得照搬的部分。本层<b>只</b>向它借一样东西：{@link TimelineService#getStats(long)}，
 * 好让两个页面的统计数字出自同一个实现（AC5）。
 *
 * <h2>⚠️ 不套拉黑过滤，这是刻意的</h2>
 * 已登录用户点开一个自己拉黑过的人的宠物主页链接，<b>照常看得到</b>
 * （2026-08-16 产品拍板，架构 AD-1 Rule 9）。V1.1.4 那条「拉黑后进不去对方主页」管的是
 * <b>社区个人主页</b>（从帖子/评论/迷你卡进去的人际入口），本层管的是
 * <b>宠物主页分享链接</b>（拿 token 从站外进来的内容页）。
 * <b>发现两处口径不一致时不要「顺手对齐」。</b>
 * 实现上本层什么都不用做 —— 它不复用 Feed 查询，天然不继承那层过滤。
 */
@Service
public class VisitorProjectionService {

    private final ProfileService profileService;
    private final AccountQueryService accountQueryService;
    private final ContentService contentService;
    private final MilestoneService milestoneService;
    /** 🛡 只为复用 {@code getStats}，让两个页面的数字出自同一个实现。不借它的任何健康相关能力。 */
    private final TimelineService timelineService;

    public VisitorProjectionService(ProfileService profileService,
            AccountQueryService accountQueryService, ContentService contentService,
            MilestoneService milestoneService, TimelineService timelineService) {
        this.profileService = profileService;
        this.accountQueryService = accountQueryService;
        this.contentService = contentService;
        this.milestoneService = milestoneService;
        this.timelineService = timelineService;
    }

    /**
     * 按分享 token 取可见的档案。
     *
     * <p>🛡 <b>四种失效情况返回同一个 empty</b>，调用方无从区分：token 不存在 / 档案已删 /
     * 账号已注销 / 账号被封号。防枚举 —— 不能让人拿一堆 token 试出「哪个曾经存在过」。
     *
     * <p>⚠️ 「封号也算失效」是 2026-08-17 产品追加的，判定收在
     * {@link AccountQueryService#isActive(long)} 里，本层<b>不另写一套</b>。
     */
    @Transactional(readOnly = true)
    public Optional<PetProfile> findVisibleProfile(String cardToken) {
        return profileService.findByCardToken(cardToken)
                .filter(p -> accountQueryService.isActive(p.getOwnerId()));
    }

    /**
     * 档案主人的公开昵称（页面上的「和 <b>Aurel</b> 在一起 128 天」）。
     *
     * <p>放在这一层，是为了让调用方<b>不必自己持有 {@link AccountQueryService}</b> ——
     * 那个服务同时能查到邮箱、状态、封号原因等一堆访客无权知道的东西。
     * 出口收在这里，外面就只拿得到一个昵称。查不到主人时返回 {@code null}（页面该段不渲染）。
     */
    @Transactional(readOnly = true)
    public String ownerNickname(PetProfile profile) {
        var view = accountQueryService.findAuthorViews(List.of(profile.getOwnerId()))
                .get(profile.getOwnerId());
        return view != null ? view.nickname() : null;
    }

    /**
     * 时间线投影：<b>只有访客有权看的那几类</b>。
     *
     * <p>🛡 <b>类④（健康记录 / 问诊存档）整类不在结果里</b>，而且因为本类不持有那些仓库，
     * 它<b>在结构上也取不到</b> —— 不是「过滤掉了」，是「根本拿不到」。
     *
     * <p>🛡 <b>只取已发布且未删除的内容</b>。作者自己能看到被下架 / 审核中的内容（让他知道发生了什么），
     * 访客态照搬的话，<b>违规内容被下架之后仍能通过分享链接对全网可见</b>。
     * ⚠️ 这里用的 {@code findRecentGrowthMomentsByEventDate} 已带 {@code PUBLISHED} 过滤；
     * {@code ContentService} 里另有几个 {@code findGrowthMoments*} 只过滤了删除、<b>没过滤审核状态</b>，
     * <b>本层绝不能改用那些</b>。
     *
     * <p>Diary 时间线<b>含作者关闭同步的私密条目</b> —— 分享宠物主页 = 授权访客查看该宠物完整 Diary
     * （PRD §2.9 §② 定稿，与 FR-83 对 H5 的拍板同口径）。这点容易搞反，别「顺手」把私密条目滤掉。
     */
    @Transactional(readOnly = true)
    public List<VisitorTimelineItem> timeline(PetProfile profile, int limit) {
        List<GrowthMomentView> moments = contentService.findRecentGrowthMomentsByEventDate(
                profile.getOwnerId(), profile.getId(), limit);

        List<VisitorTimelineItem> out = new ArrayList<>(moments.size());
        for (GrowthMomentView m : moments) {
            out.add(new VisitorTimelineItem(
                    TimelineItemType.HAPPY_MOMENT,
                    m.createdAt(),
                    m.eventDate(),
                    m.id(),
                    strippedImages(m.imageUrls()),
                    m.text(),
                    null, null, null));
        }
        return out;
    }

    /**
     * 统计投影 —— <b>复用作者态的计算，只下发三个数</b>。
     *
     * <p>🛡 {@link ArchiveStatsResponse} 有第 5 个字段 {@code healthRecordCount}（健康记录条数）。
     * 「复用统计实现」<b>不等于</b>「原样透传那个对象」：条数虽不是内容，却足以推断出
     * 「这只宠物有没有健康问题记录」，而 PRD §2.9 里健康记录整块都是 ❌。
     *
     * <p>⚠️ 「问诊<b>次数</b>」可下发（2026-08-06 产品确认保留），「问诊<b>记录</b>」不可 —— 两件事。
     */
    @Transactional(readOnly = true)
    public VisitorStats stats(PetProfile profile) {
        ArchiveStatsResponse authorStats = timelineService.getStats(profile.getOwnerId());
        return new VisitorStats(
                authorStats.happyMomentCount(),
                authorStats.consultCount(),
                authorStats.milestoneCompleted(),
                MilestoneCatalog.forType(profile.getPetType()).size());
        // healthRecordCount 到此为止，不进 VisitorStats。
    }

    /**
     * 已完成的里程碑（最近完成的在前），给访客展示徽章墙。
     *
     * <p>⚠️ <b>只在确实有已完成里程碑时才查清单</b>：{@link MilestoneService#getMilestones(long)}
     * 标了 {@code @Transactional} 且 roster 缺失时会 <b>lazy 物化写库</b>。
     * 本层服务的是<b>匿名公开页</b> —— 无条件调用等于让每个陌生人的一次 GET 都可能触发写库
     * （Story 1.2 踩过同一个坑）。
     */
    @Transactional(readOnly = true)
    public List<MilestoneItemResponse> completedMilestones(PetProfile profile, long completedCount) {
        if (completedCount <= 0) {
            return List.of();
        }
        return milestoneService.getMilestones(profile.getOwnerId()).groups().stream()
                .flatMap(g -> g.items().stream())
                .filter(MilestoneItemResponse::completed)
                .sorted(Comparator.comparing(MilestoneItemResponse::completedAt,
                        Comparator.nullsLast(Comparator.reverseOrder())))
                .toList();
    }

    /** 对外图一律经服务端去 EXIF 分发（拍摄地点等元数据不得随图外泄）。 */
    private static List<String> strippedImages(List<String> raw) {
        if (raw == null || raw.isEmpty()) {
            return List.of();
        }
        List<String> out = new ArrayList<>(raw.size());
        for (String url : raw) {
            out.add(AliyunOssClient.exifStrippedDeliveryUrl(url));
        }
        return out;
    }
}
