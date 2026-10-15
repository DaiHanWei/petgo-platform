package com.tailtopia.content.rank;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;
import org.junit.jupiter.api.Test;

/**
 * L0（V1.3.4 Story 1.3 · FR-135）：刷新抖动系数打散。
 *
 * <p>旧实现 {@code Random(seed.hashCode() * 1_000_003L + id)} 让相邻 id 的系数只差约 1e-4，
 * 新内容同涨同落、首条刷不动。本类钉住：相邻 id 互不相关、首条种数与理想随机同量级、
 * 同一（种子, id）永远同一系数、关闭开关恰为 1.0、区间不变。全部确定性，无墙钟、无不可重放随机。
 */
class FeedRankJitterTest {

    private static final String SEED = "s:fr135";
    private static final Instant NOW = Instant.parse("2026-10-12T05:00:00Z");

    /** 1000 个相邻 id 的 rand = 1 − factor（强度 1.0 ⇒ rand 即原始均匀数）。 */
    private static double[] rands(java.util.function.LongToDoubleFunction factor) {
        double[] r = new double[1000];
        for (int i = 0; i < 1000; i++) {
            r[i] = 1.0 - factor.applyAsDouble(5000 + i);
        }
        return r;
    }

    private static int nearPairs(double[] r) {
        int near = 0;
        for (int i = 0; i + 1 < r.length; i++) {
            if (Math.abs(r[i + 1] - r[i]) < 0.001) {
                near++;
            }
        }
        return near;
    }

    private static double lag1Pearson(double[] r) {
        int n = r.length - 1;
        double mx = 0;
        double my = 0;
        for (int i = 0; i < n; i++) {
            mx += r[i];
            my += r[i + 1];
        }
        mx /= n;
        my /= n;
        double sxy = 0;
        double sxx = 0;
        double syy = 0;
        for (int i = 0; i < n; i++) {
            double dx = r[i] - mx;
            double dy = r[i + 1] - my;
            sxy += dx * dy;
            sxx += dx * dx;
            syy += dy * dy;
        }
        return sxy / Math.sqrt(sxx * syy);
    }

    // ── AC1 ─────────────────────────────────────────────────────────

    @Test
    void adjacentIdsAreUncorrelated() {
        double[] r = rands(id -> FeedRankEngine.jitterFactor(SEED, id, 1.0));

        assertThat(nearPairs(r)).as("相邻 999 对中 |Δ|<0.001 的对数").isLessThanOrEqualTo(10);
        assertThat(Math.abs(lag1Pearson(r))).as("相邻对 Pearson ρ").isLessThan(0.1);
        double a = r[0];
        double b = r[1];
        double c = r[2];
        boolean allClose = Math.abs(a - b) < 0.001 && Math.abs(b - c) < 0.001
                && Math.abs(a - c) < 0.001;
        assertThat(allClose).as("id 5000/5001/5002 三个系数不应全挤在一起").isFalse();
    }

    /** 敏感性对照：同一检查对旧公式必红 —— 证明上面那条真能抓住旧 bug。 */
    @Test
    void legacyFormulaWouldFailTheSameCheck() {
        double[] r = rands(id -> 1.0 - new java.util.Random(SEED.hashCode() * 1_000_003L + id).nextDouble());

        assertThat(nearPairs(r)).as("旧公式相邻对几乎全挤在一起").isGreaterThanOrEqualTo(900);
    }

    @Test
    void engineSourceUsesNoNonReplayableOrLcgRandom() throws IOException {
        String src = Files.readString(
                Path.of("src/main/java/com/tailtopia/content/rank/FeedRankEngine.java"),
                StandardCharsets.UTF_8);
        // AC1.2：注释里的字样不算 —— 先剥掉块注释与行注释再扫调用形式
        String code = src.replaceAll("(?s)/\\*.*?\\*/", "").replaceAll("//[^\\n]*", "");

        for (String re : List.of("new\\s+(java\\.util\\.)?Random\\s*\\(", "Math\\.random\\s*\\(",
                "ThreadLocalRandom\\.")) {
            assertThat(Pattern.compile(re).matcher(code).find()).as(re).isFalse();
        }
    }

    // ── AC2：离线模拟 ───────────────────────────────────────────────

    private static RankParams simParams() {
        RankParams d = RankParams.defaults(50);
        return new RankParams(d.freshnessWeight(), d.interactionWeight(), d.commentWeight(),
                d.interactionP95(), d.honorBoost(), 0.4, d.mainSpeciesQuota(),
                d.otherSpeciesQuota(), d.generalQuota(), d.maxSameAttributeRun(),
                d.maxSameAuthorRun(), d.maxSameAuthorPerWindow(), d.maxSameOtherSpeciesRun(),
                d.eduFreshnessHalfLifeHours());
    }

    private static List<RankCandidate> simCandidates() {
        List<RankCandidate> out = new ArrayList<>();
        for (long id = 5001; id <= 6000; id++) {
            out.add(new RankCandidate(id, id, FeedAttribute.FUN, null,
                    NOW.minus(Duration.ofMinutes((6000 - id) * 10)), (id * 7919) % 30, 0));
        }
        return out;
    }

    private static String refreshSeed(int i) {
        return FeedRankCursor.SEED_PREFIX + Long.toString(1_760_000_000_000L + i * 3_000L, 36);
    }

    private static int maxFreq(List<Long> firsts) {
        Map<Long, Integer> freq = new HashMap<>();
        for (Long id : firsts) {
            freq.merge(id, 1, Integer::sum);
        }
        return freq.values().stream().mapToInt(Integer::intValue).max().orElse(0);
    }

    @Test
    void firstItemVariesLikeIdealRandomAcrossFiftyRefreshes() {
        FeedRankEngine engine = new FeedRankEngine();
        RankParams params = simParams();
        List<RankCandidate> candidates = simCandidates();

        List<Long> actual = new ArrayList<>();
        List<Long> ideal = new ArrayList<>();
        for (int i = 0; i < 50; i++) {
            FeedRankEngine.Result r = engine.rank(new FeedRankEngine.Input(candidates, null, Map.of(),
                    Set.of(), Map.of(), NOW, params, AttributeTemplate.defaultSchedule(),
                    refreshSeed(i)), 1);
            actual.add(r.picked().get(0).id());

            java.util.Random u = new java.util.Random(31L * i + 17);
            long best = -1;
            double bestScore = Double.NEGATIVE_INFINITY;
            for (RankCandidate c : candidates) { // id 升序
                double s = FeedRankScorer.finalScore(c, NOW, params, 1.0, 1.0, 1.0)
                        * (1 - 0.4 * u.nextDouble());
                if (s > bestScore) { // 严格大于：同分 id 小者胜
                    bestScore = s;
                    best = c.id();
                }
            }
            ideal.add(best);
        }

        long distinctActual = actual.stream().distinct().count();
        long distinctIdeal = ideal.stream().distinct().count();
        String msg = "新算法首条 " + distinctActual + " 种 / 最大频次 " + maxFreq(actual)
                + "；理想 " + distinctIdeal + " 种 / 最大频次 " + maxFreq(ideal);
        assertThat(distinctActual).as(msg).isGreaterThanOrEqualTo((long) Math.max(5, Math.ceil(distinctIdeal / 2.0)));
        assertThat(maxFreq(actual)).as(msg).isLessThanOrEqualTo(25);
    }

    // ── AC3 / AC4 / AC5 ─────────────────────────────────────────────

    @Test
    void sameSeedAndIdAlwaysSameFactor() {
        for (long id = 1; id <= 100; id++) {
            assertThat(Double.doubleToRawLongBits(FeedRankEngine.jitterFactor(SEED, id, 0.4)))
                    .isEqualTo(Double.doubleToRawLongBits(FeedRankEngine.jitterFactor(SEED, id, 0.4)));
        }
    }

    @Test
    void zeroStrengthOrNullSeedIsExactlyOne() {
        assertThat(FeedRankEngine.jitterFactor(SEED, 42, 0)).isEqualTo(1.0);
        assertThat(FeedRankEngine.jitterFactor(SEED, 42, -0.1)).isEqualTo(1.0);
        assertThat(FeedRankEngine.jitterFactor(null, 42, 0.4)).isEqualTo(1.0);
    }

    @Test
    void factorStaysInHalfOpenRange() {
        for (long id = 1; id <= 10_000; id++) {
            double f = FeedRankEngine.jitterFactor("s:range", id, 0.4);
            assertThat(f).isGreaterThan(0.6).isLessThanOrEqualTo(1.0);
            double g = FeedRankEngine.jitterFactor("s:range", id, 1.0);
            assertThat(g).isGreaterThan(0.0).isLessThanOrEqualTo(1.0);
        }
    }
}
