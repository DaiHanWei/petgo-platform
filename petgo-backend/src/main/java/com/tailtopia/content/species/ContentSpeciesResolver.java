package com.tailtopia.content.species;

import com.tailtopia.auth.domain.AccountType;
import com.tailtopia.auth.domain.User;
import com.tailtopia.auth.repository.UserRepository;
import com.tailtopia.content.domain.ContentPost;
import com.tailtopia.content.domain.ContentType;
import com.tailtopia.content.service.ContentPetQuery;
import com.tailtopia.profile.domain.PetProfile;
import com.tailtopia.profile.repository.PetProfileRepository;
import java.util.Collection;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 内容物种归属的推导（V1.1.6 Story 14.1 · T6）。
 *
 * <h2>优先级链</h2>
 * <pre>
 *   ① 行级覆写 species_override（运营）
 *   ② 作者账号物种定位 account_species（仅虚拟账号；NULL 读作 GENERAL）
 *   ③ 内容关联：Diary（GROWTH_MOMENT）按 content_pets 关联宠物的物种集合
 *   ④ 作者全部宠物：Moment / Tips（及无关联宠物的 Diary）按作者名下全部宠物的物种集合
 *   ⑤ 无 → NONE（引擎消费时映射 GENERAL，见 SpeciesBucket）
 * </pre>
 * V1.4.0 multi-pet Story 3.2（AD-11）：③④ 的物种集合去重后 <b>1 种 → 该物种、≥ 2 种 → GENERAL（通用池）</b>、
 * 0 种 → 下一级；来源一律记 {@link SpeciesSource#PET_PROFILE}（不新增枚举值）。只读宠物计入，已删宠物物理删除自然不在。
 *
 * <h2>🔴 读时推导，不是发布时快照（AC2 / OQ-20）</h2>
 * <b>这是本 story 杠杆最高的地方</b>：改完账号定位，该号名下**全部历史内容**的物种归属
 * 立即生效，<b>零回填</b>。
 *
 * <p>快照方案（发布时把物种写死进内容行）每次改定位都要跑一次历史回填 ——
 * 而运营改定位这件事本身就是"我发现这个号其实一直在发猫内容"，
 * 需要立刻对历史生效才有意义。产品已确认接受读时推导。
 *
 * <p>⚠️ <b>代价写在这里</b>：内容列表每页要多查一批作者与宠物档案。
 * 所以本类提供**批量**接口（{@link #resolveAll}），一次把一页内容的作者与档案查完 ——
 * 逐条 resolve 会变成 N+1，而后台内容列表是运营最常打开的一页。
 */
@Service
public class ContentSpeciesResolver {

    private final UserRepository users;
    private final PetProfileRepository pets;
    private final ContentPetQuery contentPets;

    public ContentSpeciesResolver(UserRepository users, PetProfileRepository pets,
            ContentPetQuery contentPets) {
        this.users = users;
        this.pets = pets;
        this.contentPets = contentPets;
    }

    /** 一条内容的推导（不知内容类型 ⇒ 按 ④ 作者全部宠物规则）。 */
    @Transactional(readOnly = true)
    public ResolvedSpecies resolve(String rowOverride, Long authorId) {
        return resolveAll(List.of(new Input(0L, rowOverride, authorId)))
                .getOrDefault(0L, ResolvedSpecies.NONE);
    }

    /** 一条内容的推导（带类型：Diary 走 ③ 关联宠物）。 */
    @Transactional(readOnly = true)
    public ResolvedSpecies resolve(ContentPost post) {
        long id = post.getId() == null ? 0L : post.getId();
        return resolveAll(List.of(new Input(id, post.getSpeciesOverride(), post.getAuthorId(),
                post.getType()))).getOrDefault(id, ResolvedSpecies.NONE);
    }

    /**
     * 批量推导的输入。
     *
     * @param contentId 即 postId（Diary 的 ③ 按它读 {@code content_pets}）
     * @param type      内容类型；{@code null} ⇒ 按 ④ 作者全部宠物规则（旧 3 参构造的语义）
     */
    public record Input(long contentId, String rowOverride, Long authorId, ContentType type) {

        /** 兼容旧调用（无类型 ⇒ 作者规则）。 */
        public Input(long contentId, String rowOverride, Long authorId) {
            this(contentId, rowOverride, authorId, null);
        }
    }

    /**
     * 批量推导 —— 一次把作者与宠物档案查完。
     *
     * <p>🛡 <b>不要改成逐条调 {@link #resolve}</b>：那是 N+1，而这个方法服务的是内容列表。
     */
    @Transactional(readOnly = true)
    public Map<Long, ResolvedSpecies> resolveAll(List<Input> inputs) {
        List<Long> authorIds = inputs.stream()
                .map(Input::authorId).filter(Objects::nonNull).distinct().toList();
        Map<Long, User> authors = new HashMap<>();
        if (!authorIds.isEmpty()) {
            for (User u : users.findAllById(authorIds)) {
                authors.put(u.getId(), u);
            }
        }
        // V1.4.0 multi-pet Story 3.2（AD-11）：作者名下**全部**宠物的物种集合（一次查完，含只读）。
        Map<Long, Set<String>> speciesByOwner = new HashMap<>();
        if (!authorIds.isEmpty()) {
            for (PetProfile pet : pets.findAllByOwnerIdInOrderByCreatedAtAscIdAsc(authorIds)) {
                String s = ContentSpecies.fromPetType(pet.getPetType());
                if (s != null) {
                    speciesByOwner.computeIfAbsent(pet.getOwnerId(), k -> new LinkedHashSet<>()).add(s);
                }
            }
        }
        // ③ Diary 的关联宠物：一次取关联、一次按 id 取这些宠物的物种（禁 N+1）。
        List<Long> diaryIds = inputs.stream()
                .filter(in -> in.type() == ContentType.GROWTH_MOMENT)
                .map(Input::contentId).distinct().toList();
        Map<Long, List<Long>> linkedPets = diaryIds.isEmpty() ? Map.of() : contentPets.petsOfAll(diaryIds);
        Map<Long, String> speciesByPet = new HashMap<>();
        Set<Long> linkedPetIds = new LinkedHashSet<>();
        linkedPets.values().forEach(linkedPetIds::addAll);
        if (!linkedPetIds.isEmpty()) {
            for (PetProfile pet : pets.findAllById(linkedPetIds)) {
                String s = ContentSpecies.fromPetType(pet.getPetType());
                if (s != null) {
                    speciesByPet.put(pet.getId(), s);
                }
            }
        }

        Map<Long, ResolvedSpecies> out = new HashMap<>();
        for (Input in : inputs) {
            out.put(in.contentId(), resolveOne(in, authors, speciesByOwner, linkedPets, speciesByPet));
        }
        return out;
    }

    private ResolvedSpecies resolveOne(Input in, Map<Long, User> authors,
            Map<Long, Set<String>> speciesByOwner, Map<Long, List<Long>> linkedPets,
            Map<Long, String> speciesByPet) {
        // ① 行级覆写 —— 运营手工改过的，优先级最高。
        if (ContentSpecies.isValid(in.rowOverride())) {
            return new ResolvedSpecies(in.rowOverride(), SpeciesSource.ROW_OVERRIDE);
        }
        if (in.authorId() == null) {
            return ResolvedSpecies.NONE;
        }
        User author = authors.get(in.authorId());
        if (author == null) {
            return ResolvedSpecies.NONE;
        }
        // ② 账号物种定位 —— **只对虚拟账号有意义**。
        //
        // 🔴 真实账号（含运营 IP 号）刻意**不走这条**：它们有真实宠物档案，
        //    让算法读档案比让运营给账号贴一个标签准确 ——
        //    而且 IP 号的档案会随主人换宠物而变，账号标签不会。
        if (author.getAccountType() == AccountType.VIRTUAL) {
            return new ResolvedSpecies(effectiveAccountSpecies(author),
                    SpeciesSource.ACCOUNT_SPECIES);
        }
        // ③ Diary 按关联宠物的物种集合；关联全被删（content_pets 无行）→ 回落 ④（Story 3.2 待确认 2 的默认做法）。
        if (in.type() == ContentType.GROWTH_MOMENT) {
            Set<String> linked = new LinkedHashSet<>();
            for (Long petId : linkedPets.getOrDefault(in.contentId(), List.of())) {
                String s = speciesByPet.get(petId);
                if (s != null) {
                    linked.add(s);
                }
            }
            ResolvedSpecies fromLinked = fromSpeciesSet(linked);
            if (fromLinked != null) {
                return fromLinked;
            }
        }
        // ④ 作者名下全部宠物的物种集合。
        ResolvedSpecies fromAuthor = fromSpeciesSet(speciesByOwner.get(in.authorId()));
        return fromAuthor == null ? ResolvedSpecies.NONE : fromAuthor;
    }

    /** 物种集合 → 结果：1 种 → 该物种；≥ 2 种 → GENERAL（通用池）；空 → {@code null}（走下一级）。 */
    private static ResolvedSpecies fromSpeciesSet(Collection<String> species) {
        if (species == null || species.isEmpty()) {
            return null;
        }
        Set<String> distinct = new LinkedHashSet<>(species);
        String s = distinct.size() == 1 ? distinct.iterator().next() : ContentSpecies.GENERAL;
        return new ResolvedSpecies(s, SpeciesSource.PET_PROFILE);
    }

    /**
     * 虚拟账号的有效账号定位。
     *
     * <p>🔴 <b>NULL 读作 {@code GENERAL}</b>（AC2「存量统一置为 GENERAL、无需回填」）。
     * 用读时默认而不是一条 UPDATE：那条 UPDATE 不只是跑一次的代价 ——
     * 它会让"这个号到底配过没有"永远分不出来（配成 GENERAL 与从未配过同形）。
     */
    public static String effectiveAccountSpecies(User virtualAccount) {
        String v = virtualAccount.getAccountSpecies();
        return ContentSpecies.isValid(v) ? v : ContentSpecies.GENERAL;
    }

    /** 供 13-3 的继承规则用：某账号可继承的物种（非虚拟账号 ⇒ 空）。 */
    @Transactional(readOnly = true)
    public Optional<String> accountSpeciesOf(long userId) {
        return users.findById(userId)
                .filter(u -> u.getAccountType() == AccountType.VIRTUAL)
                .map(ContentSpeciesResolver::effectiveAccountSpecies);
    }
}
