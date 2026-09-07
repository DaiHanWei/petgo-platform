package com.tailtopia.content.rank;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.function.Predicate;
import org.springframework.stereotype.Component;

/**
 * 打分与配比引擎（V1.1.6 Story 16.2）。
 *
 * <h2>🔴 为什么它刻意不碰数据库、不碰 HTTP</h2>
 * 输入是「候选内容列表 + 用户主物种 + 曝光/荣誉/限流系数 + 参数」，输出是「排好的序列」。
 * 这样配比与打分能在<b>纯单测里穷举</b>。
 *
 * <p>理由：配比算错的表现是「刷起来节奏怪」、打分算错的表现是「排序看着有点怪」——
 * 两者都<b>不会崩、不会报错</b>，在集成测试里几乎看不出来。做成纯函数是唯一能真正验证它的办法。
 *
 * <p>🛡 <b>本类不持有任何 repository</b>：所以 AC5 的「禁止逐条 COUNT」不是靠自觉，
 * 是靠<b>拿不到查询入口</b>。赞评计数由 16.3 批量取好后放进 {@link RankCandidate}。
 *
 * <h2>每个槽位怎么选</h2>
 * 按候选分从高到低找第一个满足条件、且不违反防扎堆的内容。条件按下面的顺序逐级放宽
 * （AC7 的降级链级别 1 / 2）：
 * <ol>
 *   <li>属性符合模板 + 物种桶还有配额　　　　　← 理想情况</li>
 *   <li>属性符合模板 + 通用池　　　　　　　　← 级别 2：物种池不足，放宽至通用</li>
 *   <li>属性符合模板（不限物种）　　　　　　　← 级别 2：再放宽至全池</li>
 *   <li>物种桶还有配额（不限属性）　　　　　　← 级别 1：属性池不足，其他属性补位</li>
 *   <li>无约束　　　　　　　　　　　　　　　　← 级别 1 + 2 同时放宽</li>
 * </ol>
 * 🛡 <b>级别 1、2 在当前内容体量下会经常触发，属预期行为，不告警</b>（§6.2）——
 * 为它告警等于把告警变成噪音、真出事（级别 3/4）时没人看。
 */
@Component
public class FeedRankEngine {

    /** 引擎输入。 */
    public record Input(
            List<RankCandidate> candidates,
            /** 查看者主物种；{@code null} = 拿不到物种信号（无宠物档案 / 游客）。 */
            String mainSpecies,
            /** 曝光衰减系数（16.1 供）。缺省 = 1.0。 */
            Map<Long, Double> exposureDecay,
            /** 带<b>生效中</b>装饰标签的内容 id。标签到期即不在此集合内 ⇒ 加成自动回落 1.0。 */
            Set<Long> honoredContentIds,
            /** 限流系数（Epic 17 供）。缺省 = 1.0；本 story 一律传空。 */
            Map<Long, Double> throttleFactors,
            Instant now,
            RankParams params,
            /**
             * 属性穿插排期（Story 16.4）。
             *
             * <p>⚠️ 窗口大小来自它（{@code schedule.window()}），<b>不再是常量</b> ——
             * 配比与模板由同一处产生，改配比就会真的改顺序。
             */
            AttributeSchedule schedule,
            /**
             * 刷新抖动的随机源 = <b>序列种子</b>（2026-09-01 产品拍板「刷新要明显换一批」）。
             *
             * <p>此前种子只是快照缓存键、不参与排序，排序纯确定性 ⇒ 下拉刷新「换种子重算」
             * 算出来的还是同一份 —— 表现就是"刷新没变化"。现在每条内容乘一个由
             * （种子 + 内容 id）确定的抖动系数：<b>同一种子内排序稳定</b>（翻页快照契约不变、
             * 续算不重复），换种子即换排序。{@code null} = 不抖动（既有单测全部走这里，行为一字不变）。
             */
            String shuffleSeed) {
    }

    /**
     * 引擎输出。
     *
     * @param picked              排好的序列
     * @param attributeRelaxed    属性放宽了几次（级别 1，仅供观测，不告警）
     * @param speciesRelaxed      物种放宽了几次（级别 2，仅供观测，不告警）
     * @param antiClumpOverridden 防扎堆让步了几次（池子实在挑不出干净的，见 {@link #rank}）
     */
    public record Result(List<RankCandidate> picked, int attributeRelaxed, int speciesRelaxed,
            int antiClumpOverridden) {
    }

    /**
     * 排出前 {@code wanted} 条。
     *
     * <p>🛡 <b>不空槽</b>（AC7）：逐级放宽后仍挑不出，说明候选池真的空了，直接返回已排出的部分
     * —— 而不是留一个洞或补 null。
     *
     * <p>⚠️ <b>防扎堆是 best-effort</b>：若某个槽位下全部剩余候选都违反防扎堆，
     * 仍会取其中分最高的那条并计入 {@link Result#antiClumpOverridden}。
     * 「宁可扎堆也不空槽」是有意的取舍 —— 空槽对用户是内容变少，扎堆只是节奏差一点。
     */
    public Result rank(Input in, int wanted) {
        RankParams p = in.params();
        Instant now = in.now();

        // 属性为 null 的候选剔掉（非公开的 GROWTH_MOMENT；正常路径不会出现）。
        List<Scored> pool = new ArrayList<>();
        for (RankCandidate c : in.candidates()) {
            if (c.attribute() == null) {
                continue;
            }
            double decay = factor(in.exposureDecay(), c.id());
            double honor = in.honoredContentIds() != null && in.honoredContentIds().contains(c.id())
                    ? p.honorBoost() : 1.0;
            double throttle = factor(in.throttleFactors(), c.id());
            // 刷新抖动（2026-09-01）：最终分再乘一个 (1-幅度, 1] 区间的确定性系数。
            // ⚠️ 用 seeded Random 而不是 Math.random —— 同一种子必须算出同一序列
            //    （续算、级别 3 降级重算都依赖这一点），类注释禁的是**不可重放**的随机。
            double jitter = jitterFactor(in.shuffleSeed(), c.id(), p.shuffleStrength());
            pool.add(new Scored(c, SpeciesBucket.of(c.species(), in.mainSpecies()),
                    FeedRankScorer.finalScore(c, now, p, decay, honor, throttle) * jitter));
        }
        // 一次排好序，后面每个槽位顺序扫、取第一个合格的 —— 天然就是「分最高的那条」。
        // ⚠️ 分数与槽位无关，所以只排一次；每槽重算是纯浪费。
        // id 做 tie-breaker：同分时顺序确定，否则同一种子两次生成的序列可能不同（那会让快照失去意义）。
        pool.sort(Comparator.comparingDouble(Scored::score).reversed()
                .thenComparing(s -> s.candidate().id()));

        List<RankCandidate> picked = new ArrayList<>();
        int attributeRelaxed = 0;
        int speciesRelaxed = 0;
        int antiClumpOverridden = 0;

        Map<SpeciesBucket, Integer> speciesUsed = new EnumMap<>(SpeciesBucket.class);
        Map<Long, Integer> authorInWindow = new HashMap<>();
        AttributeSchedule schedule = in.schedule();
        int window = schedule.window();

        for (int slot = 0; slot < wanted && !pool.isEmpty(); slot++) {
            if (slot % window == 0) {
                speciesUsed.clear();
                authorInWindow.clear();
            }
            FeedAttribute wantedAttr = schedule.at(slot);
            boolean speciesActive = in.mainSpecies() != null;

            Predicate<Scored> quotaOk = s -> !speciesActive
                    || speciesUsed.getOrDefault(s.bucket(), 0) < quotaOf(s.bucket(), p);

            // 五级放宽，顺序见类注释。
            List<Predicate<Scored>> attempts = List.of(
                    s -> s.candidate().attribute() == wantedAttr && quotaOk.test(s),
                    s -> s.candidate().attribute() == wantedAttr && s.bucket() == SpeciesBucket.GENERAL,
                    s -> s.candidate().attribute() == wantedAttr,
                    quotaOk,
                    s -> true);

            Scored chosen = null;
            Scored dirtyFallback = null;
            int attemptIndex = -1;
            for (int a = 0; a < attempts.size() && chosen == null; a++) {
                Predicate<Scored> ok = attempts.get(a);
                for (Scored s : pool) {
                    if (!ok.test(s)) {
                        continue;
                    }
                    if (clumps(s, picked, slot, window, p)) {
                        // 违反防扎堆 → 跳过取次高分（AC6）。留一个兜底，防止全都违反时空槽。
                        if (dirtyFallback == null) {
                            dirtyFallback = s;
                        }
                        continue;
                    }
                    chosen = s;
                    attemptIndex = a;
                    break;
                }
            }
            if (chosen == null) {
                if (dirtyFallback == null) {
                    break; // 池子真空了
                }
                chosen = dirtyFallback;
                antiClumpOverridden++;
                // ⚠️ 走兜底不算「放宽」：这条内容属性/物种本来就合规，是防扎堆让了步。
                // 把它计成放宽会让「级别 1/2 触发了多少次」这个观测指标失真。
                attemptIndex = -1;
            }
            // 放宽计数：attempt 1/2 是物种放宽，attempt 3 是属性放宽，attempt 4 是两者都放宽。
            if (attemptIndex == 1 || attemptIndex == 2) {
                speciesRelaxed++;
            } else if (attemptIndex == 3) {
                attributeRelaxed++;
            } else if (attemptIndex == 4) {
                attributeRelaxed++;
                speciesRelaxed++;
            }

            picked.add(chosen.candidate());
            pool.remove(chosen);
            speciesUsed.merge(chosen.bucket(), 1, Integer::sum);
            authorInWindow.merge(chosen.candidate().authorId(), 1, Integer::sum);
        }
        return new Result(List.copyOf(picked), attributeRelaxed, speciesRelaxed,
                antiClumpOverridden);
    }

    /** 防扎堆四条（AC6）。任一条命中即视为扎堆。 */
    private boolean clumps(Scored s, List<RankCandidate> picked, int slot, int window,
            RankParams p) {
        RankCandidate c = s.candidate();
        // 1. 同一属性连续 ≤ maxSameAttributeRun（兜底：模板已保证不相邻，此条防降级补位破坏节奏）
        if (trailing(picked, x -> x.attribute() == c.attribute()) >= p.maxSameAttributeRun()) {
            return true;
        }
        // 2. 同一作者连续 ≤ maxSameAuthorRun（防同一人连发刷屏）
        if (trailing(picked, x -> x.authorId() == c.authorId()) >= p.maxSameAuthorRun()) {
            return true;
        }
        // 3. 同一作者 10 条窗口内 ≤ maxSameAuthorPerWindow（防单个种子号占据整屏）
        int windowStart = (slot / window) * window;
        long inWindow = picked.subList(Math.min(windowStart, picked.size()), picked.size()).stream()
                .filter(x -> x.authorId() == c.authorId()).count();
        if (inWindow >= p.maxSameAuthorPerWindow()) {
            return true;
        }
        // 4. 同一非主物种连续 ≤ maxSameOtherSpeciesRun（防「偶尔看到狗」变成「连着三条狗」）
        //    ⚠️ 比的是**具体物种**而不是 OTHER 这个桶 —— 桶里可能同时有狗和其他动物，
        //    按桶比会把「一条狗 + 一条仓鼠」也判成扎堆，那不是产品要防的事。
        if (s.bucket() == SpeciesBucket.OTHER
                && trailing(picked, x -> Objects.equals(x.species(), c.species()))
                        >= p.maxSameOtherSpeciesRun()) {
            return true;
        }
        return false;
    }

    /** 已选序列<b>末尾</b>连续满足条件的条数。 */
    private static int trailing(List<RankCandidate> picked, Predicate<RankCandidate> match) {
        int n = 0;
        for (int i = picked.size() - 1; i >= 0 && match.test(picked.get(i)); i--) {
            n++;
        }
        return n;
    }

    private static int quotaOf(SpeciesBucket bucket, RankParams p) {
        return switch (bucket) {
            case MAIN -> p.mainSpeciesQuota();
            case OTHER -> p.otherSpeciesQuota();
            case GENERAL -> p.generalQuota();
        };
    }

    /**
     * 刷新抖动系数 ∈ (1-strength, 1]，由（种子, 内容 id）完全决定。
     *
     * <p>🛡 种子为 null 或幅度 ≤ 0 → 1.0（不抖动）：既有单测与「关闭抖动」的运营配置
     * 都靠这条回到纯分数排序。乘法而非加法 —— 分数为 0 的内容抖不上来。
     */
    private static double jitterFactor(String seed, long id, double strength) {
        if (seed == null || strength <= 0) {
            return 1.0;
        }
        // java.util.Random 的算法是 JDK 规范钉死的（LCG），String.hashCode 同理 ——
        // 跨 JVM、跨重启同一（种子, id）永远得到同一个数。
        double rand = new java.util.Random(seed.hashCode() * 1_000_003L + id).nextDouble();
        return 1.0 - strength * rand;
    }

    /** 缺省系数 = 1.0（🛡 不是 0 —— 那会把内容分整条抹平）。 */
    private static double factor(Map<Long, Double> factors, long id) {
        if (factors == null) {
            return 1.0;
        }
        Double v = factors.get(id);
        return v == null ? 1.0 : v;
    }

    /** 池内一项：候选 + 相对查看者的物种桶 + 最终分。 */
    private record Scored(RankCandidate candidate, SpeciesBucket bucket, double score) {
    }
}
