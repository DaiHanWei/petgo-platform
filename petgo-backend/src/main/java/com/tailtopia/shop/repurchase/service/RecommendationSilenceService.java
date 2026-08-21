package com.tailtopia.shop.repurchase.service;

import com.tailtopia.profile.domain.ArchiveDecision;
import com.tailtopia.profile.domain.HealthRecordType;
import com.tailtopia.profile.domain.PetProfile;
import com.tailtopia.profile.repository.HealthEventRepository;
import com.tailtopia.profile.repository.HealthRecordRepository;
import com.tailtopia.profile.repository.PetProfileRepository;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.EnumSet;
import java.util.Set;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 推荐静默期（V1.4.0 · {@code design_handoff_ecommerce/03_screens_recommendation.md} 屏 5 第 4 块）。
 *
 * <p><b>规则</b>：宠物出现负面健康事件时，该事件<b>前后各 7 天</b>内不出现任何商品推荐、
 * 补货触发卡、软提示或征询卡。
 *
 * <p>🔴 <b>优先级最高，覆盖所有开关</b> —— 包括用户主动开启的推荐项。设计文档把它列为
 * 推荐系统的实现优先级第 1 位，理由是「无 UI 也必须先有，否则第一次在离世记录旁推商品
 * 就会造成不可逆的口碑损失」。本类因此<b>先于任何推荐 UI 落地</b>。
 *
 * <p><b>窗口取 ±7 天而不是「过去 7 天」</b>。两半的可达性目前并不对称，实测如下：
 * <ul>
 *   <li><b>过去半边</b>：两个数据源都可达 —— 7 天内的手术/问诊记录使今天静默。</li>
 *   <li><b>未来半边</b>：{@code HealthRecordCreateRequest.eventDate} 带
 *       {@code @PastOrPresent}，<b>健康记录填不了未来日期</b>，这半边对它不可达；
 *       而 {@code ArchiveDecisionRequest.eventDate} <b>没有</b>该约束，健康事件可达。</li>
 * </ul>
 * 仍按对称窗口实现，两个理由：设计文档写的就是「该记录<b>前后</b> 7 天」；以及若日后放开
 * {@code @PastOrPresent}（预约手术是很自然的需求），这里<b>一行都不用改</b>就自动生效。
 *
 * <h2>🔴 数据源缺口（实现前已知，需产品闭环）</h2>
 *
 * 设计文档要求的三类触发源是 {@code sakit}（生病）/ {@code operasi}（手术）/
 * {@code kehilangan}（走失、离世），但本仓库的 {@link HealthRecordType} 只有
 * {@code VACCINE / DEWORM / MENSTRUATION / NEUTER / CUSTOM}，三类**没有一类是直接对应的**。
 * 现按可得数据落地：
 *
 * <table>
 *   <caption>设计要求 → 本实现的数据源</caption>
 *   <tr><th>设计</th><th>本实现取自</th><th>覆盖度</th></tr>
 *   <tr><td>{@code operasi} 手术</td><td>{@link HealthRecordType#NEUTER}（绝育）</td>
 *       <td>⚠️ <b>偏窄</b>：只覆盖绝育，其它手术无类型可落</td></tr>
 *   <tr><td>{@code sakit} 生病</td><td>已存档的 {@code HealthEvent}（问诊存档）</td>
 *       <td>✅ 代理信号良好：用户会为宠物问诊，通常就是不舒服</td></tr>
 *   <tr><td>{@code kehilangan} 走失 / 离世</td><td><b>无</b></td>
 *       <td>❌ <b>完全缺失</b>：档案没有离世/走失状态字段</td></tr>
 * </table>
 *
 * 🔴 <b>{@code kehilangan} 是三类里后果最严重的一类，而它恰恰没有数据源</b> ——
 * 补齐需要新增 {@code HealthRecordType} 值（Flyway 改 CHECK 约束）或给档案加状态字段，
 * 两者都是 PRD 级决策，不在本轮范围。补齐后只需往 {@link #NEGATIVE_RECORD_TYPES}
 * 加值 / 在 {@link #isSilenced} 里加一个 or 分支，<b>调用方一行不用改</b> —— 本类的存在
 * 就是为了让那次补齐是加一行而不是一次翻修。
 *
 * <h2>判定为何刻意从宽</h2>
 *
 * 已存档健康事件<b>不按 {@code aiLevel} 过滤</b>（GREEN 的也算）。误静默的代价是少几次
 * 曝光，漏静默的代价是在坏消息旁边卖东西 —— 两者不对称，故取保守侧。
 * 若日后静默率被证实过高，收紧的正确做法是排除 GREEN，而不是缩短窗口。
 */
@Service
public class RecommendationSilenceService {

    private static final Logger log = LoggerFactory.getLogger(RecommendationSilenceService.class);

    /** 静默半径（天）。事件日前后各这么多天。 */
    public static final int SILENCE_RADIUS_DAYS = 7;

    /**
     * 命中即静默的健康记录类型。
     *
     * <p>⚠️ {@code VACCINE}（疫苗）与 {@code DEWORM}（驱虫）<b>刻意不在此列</b> ——
     * 它们是常规保健，不是坏消息；把它们算进来会让静默期几乎常驻，
     * 反而使这条规则失去意义。{@code MENSTRUATION} 同理。
     */
    private static final Set<HealthRecordType> NEGATIVE_RECORD_TYPES =
            EnumSet.of(HealthRecordType.NEUTER);

    private final PetProfileRepository profiles;
    private final HealthRecordRepository healthRecords;
    private final HealthEventRepository healthEvents;

    public RecommendationSilenceService(PetProfileRepository profiles,
            HealthRecordRepository healthRecords, HealthEventRepository healthEvents) {
        this.profiles = profiles;
        this.healthRecords = healthRecords;
        this.healthEvents = healthEvents;
    }

    /**
     * 该用户当前是否处于静默期。
     *
     * <p>无档案 → {@code false}：没有宠物就没有健康事件，也就无从静默。
     */
    @Transactional(readOnly = true)
    public boolean isSilenced(long userId) {
        PetProfile pet = profiles.findByOwnerId(userId).orElse(null);
        if (pet == null) {
            return false;
        }
        // 与 MeRepurchaseController 同口径取 UTC 今天（本仓库无 Clock bean，
        // 不为一处判定引入全局时钟概念；可测性由下面的包内可见重载承担）。
        return isSilencedForPet(pet.getId(), LocalDate.now(ZoneOffset.UTC));
    }

    /**
     * 指定宠物在指定日期是否处于静默期。
     *
     * <p>包内可见供测试注入固定日期 —— 静默期是日期边界逻辑，必须能测「第 7 天静默、
     * 第 8 天恢复」这种一天之差，不能依赖真实时钟。
     */
    boolean isSilencedForPet(long petProfileId, LocalDate today) {
        LocalDate from = today.minusDays(SILENCE_RADIUS_DAYS);
        LocalDate to = today.plusDays(SILENCE_RADIUS_DAYS);

        boolean silenced = healthRecords.existsByPetProfileIdAndTypeInAndEventDateBetween(
                        petProfileId, NEGATIVE_RECORD_TYPES, from, to)
                || healthEvents.existsByPetIdAndArchiveDecisionAndEventDateBetween(
                        petProfileId, ArchiveDecision.ARCHIVED, from, to);

        if (silenced) {
            // 🔒 只记「发生了一次静默」，**不记宠物 id、不记事件类型、不记日期** ——
            //    这三者任一进日志都等于把健康数据落到日志系统（NFR-5 / 架构 §Enforcement）。
            //    设计文档要的 `reco_silence_period_suppressed` 指标由这条日志聚合，
            //    🔴 **不做成客户端埋点**：客户端要能上报，就必须先知道自己被静默了，
            //    而「你正处于静默期」本身就是健康状态的泄露。
            log.info("recommendation suppressed by silence period");
        }
        return silenced;
    }
}
