package com.tailtopia.shop.repurchase.service;

import com.tailtopia.profile.domain.PetProfile;
import com.tailtopia.profile.domain.PetType;
import com.tailtopia.profile.repository.PetProfileRepository;
import com.tailtopia.shop.domain.AgeStage;
import com.tailtopia.shop.domain.BodySize;
import com.tailtopia.shop.domain.ShopProduct;
import com.tailtopia.shop.domain.ShopSku;
import com.tailtopia.shop.domain.Species;
import com.tailtopia.shop.repository.ShopProductRepository;
import com.tailtopia.shop.repository.ShopSkuRepository;
import com.tailtopia.shop.repurchase.domain.ProfileFacts;
import com.tailtopia.shop.repurchase.dto.RecommendationView;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 档案推荐规则引擎（Story 6.2，FR-107）。
 *
 * <p>四步：<b>① 按适用物种硬过滤 → ② 按适用年龄段匹配 → ③ 按适用体型匹配 →
 * ④ 按运营权重排序取前 4–6 个。</b>
 *
 * <p>🔴 <b>规则式，不引入个性化算法</b>：SKU 上限 30，规则式已足够；算法要为冷启动、
 * 可解释性与长期调参负责，这个规模下带不来相应收益。
 *
 * <p>🔴 <b>每条结果必带推荐理由</b> —— 不可解释的推荐在信任驱动的产品里是负资产。
 *
 * <p>🔴 <b>档案不完整时降级为按物种推荐，不报错、不返回空</b>，并置 {@code degraded=true}
 * 供前端展示补全引导卡（Story 6.5）—— 那是存量用户回填体重的唯一入口（L-9）。
 */
@Service
public class ProfileRecommendationService {

    /** 结果条数区间（FR-107：4–6 个）。不足下限也照给 —— 少几个好过一个都不给。 */
    public static final int MAX_ITEMS = 6;

    private final PetProfileRepository profiles;
    private final ShopProductRepository products;
    private final ShopSkuRepository skus;
    private final RecommendationSilenceService silence;

    /** 🔴 犬猫年龄阈值可后台配置（FR-107）。这里经配置注入，默认取 PRD 值。 */
    private final int puppyMaxYears;
    private final int adultMaxYears;

    public ProfileRecommendationService(PetProfileRepository profiles,
            ShopProductRepository products, ShopSkuRepository skus,
            RecommendationSilenceService silence,
            @Value("${petgo.shop.reco.puppy-max-years:1}") int puppyMaxYears,
            @Value("${petgo.shop.reco.adult-max-years:7}") int adultMaxYears) {
        this.profiles = profiles;
        this.products = products;
        this.skus = skus;
        this.silence = silence;
        this.puppyMaxYears = puppyMaxYears;
        this.adultMaxYears = adultMaxYears;
    }

    /**
     * 为该用户的宠物出推荐。
     *
     * <p>🔴 <b>直接用该用户的唯一宠物，不做宠物选择器</b>（L-11 单账号单宠物硬约束）。
     * ⚠️ <b>1.3.0 多宠物后启用</b>：届时入参要换成 petProfileId，并在前端加选择器。
     *
     * @return 永不为 null；无档案时返回空结果 + {@code degraded=true}
     */
    @Transactional(readOnly = true)
    public RecommendationView recommendFor(long userId) {
        PetProfile pet = profiles.findByOwnerId(userId).orElse(null);
        if (pet == null) {
            // 未建档：前端用建档引导卡替换整区（FR-93 状态矩阵），这里不编造推荐
            return RecommendationView.empty(null, true, "PROFILE");
        }
        // 🔴 静默期优先于一切（含用户主动开启的推荐项）—— 见 RecommendationSilenceService。
        //    守卫放在**服务层而不是 controller**：controller 只是当前的唯一调用方，
        //    放这里才保证以后新增的调用方也绕不过去。
        if (silence.isSilenced(userId)) {
            // 🔒 返回 degraded=false / missing=NONE / items=[] —— 前端据此**整区不渲染**。
            //    刻意不给「静默中」之类的标记：客户端一旦能识别静默态，就等于知道了
            //    这只宠物近期有负面健康事件，那是健康数据越过了 App 边界。
            //    也刻意不返回 degraded=true —— 那会让前端弹出「补全档案，推荐更准」引导卡，
            //    在离世/手术记录旁边劝人完善资料以便更好地推荐，比直接推商品更冒犯。
            return RecommendationView.empty(pet.getName(), false, "NONE");
        }
        ProfileFacts facts = factsOf(pet);
        return recommend(facts);
    }

    /** 纯函数式入口，便于单测四步过滤（不碰 DB 的部分见 {@link #filter}）。 */
    @Transactional(readOnly = true)
    public RecommendationView recommend(ProfileFacts facts) {
        List<ShopProduct> active = products.findByActiveTrueOrderBySortWeightDescIdDesc();
        List<ShopProduct> matched = filter(active, facts);
        List<RecommendationView.Item> items = new ArrayList<>();
        for (ShopProduct p : matched) {
            items.add(new RecommendationView.Item(p.getPublicToken(), p.getName(), p.getBrand(),
                    p.getMainImageKey(), minPriceOf(p.getId()), reasonFor(p, facts)));
        }
        return new RecommendationView(!facts.isComplete(), facts.missing(), facts.petName(),
                items);
    }

    /**
     * 四步过滤（纯函数，无 IO —— 单测直接喂列表即可覆盖各步）。
     *
     * <p>🔴 <b>只有物种是硬过滤</b>：年龄与体型在档案缺失时<b>整步跳过</b>，
     * 而不是「当作 UNIVERSAL 去匹配」—— 后者会把只适合幼犬的粮推给一只年龄未知的狗。
     */
    public List<ShopProduct> filter(List<ShopProduct> candidates, ProfileFacts facts) {
        return candidates.stream()
                // ① 物种硬过滤。UNIVERSAL 商品对所有物种可见。
                .filter(p -> matchesSpecies(p.getSpecies(), facts.species()))
                // ② 年龄段：档案有年龄才参与过滤
                .filter(p -> facts.ageStage() == null
                        || matchesAge(p.getAgeStage(), facts.ageStage()))
                // ③ 体型：档案有体重才参与过滤
                .filter(p -> facts.bodySize() == null
                        || matchesSize(p.getBodySize(), facts.bodySize()))
                // ④ 运营权重排序（仓储已按 sortWeight desc, id desc 给出稳定序），取前 N
                .sorted(Comparator.comparingInt(ShopProduct::getSortWeight).reversed()
                        .thenComparing(Comparator.comparing(ShopProduct::getId).reversed()))
                .limit(MAX_ITEMS)
                .toList();
    }

    // ---------- 匹配规则 ----------

    private static boolean matchesSpecies(Species productSpecies, Species petSpecies) {
        if (productSpecies == null) {
            return false;   // 🔴 未标物种的商品不参与推荐（宁可少推，不可推错物种的粮）
        }
        return productSpecies == Species.UNIVERSAL || petSpecies == null
                || productSpecies == petSpecies;
    }

    private static boolean matchesAge(AgeStage productStage, AgeStage petStage) {
        return productStage == null || productStage == AgeStage.UNIVERSAL
                || productStage == petStage;
    }

    private static boolean matchesSize(BodySize productSize, BodySize petSize) {
        return productSize == null || productSize == BodySize.UNIVERSAL || productSize == petSize;
    }

    /**
     * 推荐理由（FR-107）。
     *
     * <p>🔴 <b>理由必须来自实际用上的那几个维度</b> —— 编一句放之四海皆准的话
     * （「精选好物」之类）等于没有理由，用户一眼看得出。
     */
    static String reasonFor(ShopProduct p, ProfileFacts facts) {
        List<String> parts = new ArrayList<>();
        parts.add(speciesWord(facts.species()));
        if (facts.ageStage() != null && p.getAgeStage() != null
                && p.getAgeStage() != AgeStage.UNIVERSAL) {
            parts.add(ageWord(facts.ageStage()));
        }
        if (facts.bodySize() != null && p.getBodySize() != null
                && p.getBodySize() != BodySize.UNIVERSAL) {
            parts.add(sizeWord(facts.bodySize()));
        }
        return "Untuk " + String.join(" ", parts);
    }

    /** 印尼语词表。文案面向用户，与 App 的 id 语种一致。 */
    private static String speciesWord(Species s) {
        if (s == Species.CAT) {
            return "kucing";
        }
        return s == Species.DOG ? "anjing" : "hewan peliharaan";
    }

    private static String ageWord(AgeStage a) {
        return switch (a) {
            case PUPPY -> "muda";
            case ADULT -> "dewasa";
            case SENIOR -> "senior";
            case UNIVERSAL -> "semua usia";
        };
    }

    private static String sizeWord(BodySize b) {
        return switch (b) {
            case SMALL -> "< 10 kg";
            case MEDIUM -> "10–25 kg";
            case LARGE -> "> 25 kg";
            case UNIVERSAL -> "semua ukuran";
        };
    }

    // ---------- 档案 → 事实 ----------

    ProfileFacts factsOf(PetProfile pet) {
        return new ProfileFacts(
                speciesOf(pet.getPetType()),
                ProfileFacts.ageStageOf(pet.getBirthday(), LocalDate.now(), puppyMaxYears,
                        adultMaxYears),
                ProfileFacts.bodySizeOf(pet.getWeightKg()),
                pet.getWeightKg(),
                pet.getName());
    }

    /** {@code PetType.OTHER}（非猫非犬）→ UNIVERSAL：只推通用商品，不硬猜成猫或狗。 */
    private static Species speciesOf(PetType t) {
        if (t == PetType.CAT) {
            return Species.CAT;
        }
        return t == PetType.DOG ? Species.DOG : Species.UNIVERSAL;
    }

    private long minPriceOf(Long productId) {
        return skus.findByProductIdOrderByIdAsc(productId).stream()
                .mapToLong(ShopSku::getPrice).min().orElse(0L);
    }
}
