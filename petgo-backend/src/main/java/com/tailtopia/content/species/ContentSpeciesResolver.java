package com.tailtopia.content.species;

import com.tailtopia.auth.domain.AccountType;
import com.tailtopia.auth.domain.User;
import com.tailtopia.auth.repository.UserRepository;
import com.tailtopia.profile.domain.PetProfile;
import com.tailtopia.profile.repository.PetProfileRepository;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 内容物种归属的推导（V1.1.6 Story 14.1 · T6）。
 *
 * <h2>优先级链</h2>
 * <pre>
 *   行级覆写 > 作者账号物种定位（仅虚拟账号）> 作者宠物档案 > 无
 * </pre>
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

    public ContentSpeciesResolver(UserRepository users, PetProfileRepository pets) {
        this.users = users;
        this.pets = pets;
    }

    /** 一条内容的推导。 */
    @Transactional(readOnly = true)
    public ResolvedSpecies resolve(String rowOverride, Long authorId) {
        return resolveAll(List.of(new Input(0L, rowOverride, authorId)))
                .getOrDefault(0L, ResolvedSpecies.NONE);
    }

    /** 批量推导的输入。 */
    public record Input(long contentId, String rowOverride, Long authorId) {
    }

    /**
     * 批量推导 —— 一次把作者与宠物档案查完。
     *
     * <p>🛡 <b>不要改成逐条调 {@link #resolve}</b>：那是 N+1，而这个方法服务的是内容列表。
     */
    @Transactional(readOnly = true)
    public Map<Long, ResolvedSpecies> resolveAll(List<Input> inputs) {
        List<Long> authorIds = inputs.stream()
                .map(Input::authorId).filter(java.util.Objects::nonNull).distinct().toList();
        Map<Long, User> authors = new HashMap<>();
        if (!authorIds.isEmpty()) {
            for (User u : users.findAllById(authorIds)) {
                authors.put(u.getId(), u);
            }
        }
        Map<Long, PetProfile> petsByOwner = new HashMap<>();
        if (!authorIds.isEmpty()) {
            for (PetProfile p : pets.findByOwnerIdIn(authorIds)) {
                petsByOwner.put(p.getOwnerId(), p);
            }
        }

        Map<Long, ResolvedSpecies> out = new HashMap<>();
        for (Input in : inputs) {
            out.put(in.contentId(), resolveOne(in, authors, petsByOwner));
        }
        return out;
    }

    private ResolvedSpecies resolveOne(Input in, Map<Long, User> authors,
            Map<Long, PetProfile> petsByOwner) {
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
        // ③ 作者宠物档案。
        PetProfile pet = petsByOwner.get(in.authorId());
        String fromPet = pet == null ? null : ContentSpecies.fromPetType(pet.getPetType());
        return fromPet == null ? ResolvedSpecies.NONE
                : new ResolvedSpecies(fromPet, SpeciesSource.PET_PROFILE);
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
