package com.tailtopia.content.autocomment;

import com.tailtopia.auth.domain.User;
import com.tailtopia.content.species.ContentSpecies;
import com.tailtopia.content.species.ContentSpeciesResolver;
import com.tailtopia.social.read.UserHideRelationReader;
import java.util.List;
import java.util.concurrent.ThreadLocalRandom;
import java.util.function.IntUnaryOperator;
import org.springframework.stereotype.Component;

/**
 * 给一个帖子挑评论用的虚拟账号：
 * <ol>
 *   <li>排除被楼主隐藏（拉黑 / 举报）的号——否则评论能发出去、但楼主收不到通知，也违背楼主意愿；</li>
 *   <li>帖子物种明确（猫 / 狗 / 其他）时，优先同物种定位的号；</li>
 *   <li>没有同物种的号，退到 GENERAL 定位的号；再没有，剩下的都可以；</li>
 *   <li>在选中的那一档里随机取一个。</li>
 * </ol>
 */
@Component
public class VirtualIdentityPicker {

    private final UserHideRelationReader hides;
    private final IntUnaryOperator random;

    public VirtualIdentityPicker(UserHideRelationReader hides) {
        this(hides, bound -> ThreadLocalRandom.current().nextInt(bound));
    }

    VirtualIdentityPicker(UserHideRelationReader hides, IntUnaryOperator random) {
        this.hides = hides;
        this.random = random;
    }

    /** @return 选中的虚拟账号；没有可用的返回 null */
    public User pick(List<User> pool, long postAuthorId, String species) {
        List<User> usable = pool.stream()
                .filter(u -> u.getId() != postAuthorId)
                .filter(u -> !hides.isHidden(postAuthorId, u.getId()))
                .toList();
        if (usable.isEmpty()) {
            return null;
        }
        List<User> tier = List.of();
        if (species != null && !ContentSpecies.GENERAL.equals(species)) {
            tier = usable.stream()
                    .filter(u -> species.equals(ContentSpeciesResolver.effectiveAccountSpecies(u)))
                    .toList();
        }
        if (tier.isEmpty()) {
            tier = usable.stream()
                    .filter(u -> ContentSpecies.GENERAL.equals(ContentSpeciesResolver.effectiveAccountSpecies(u)))
                    .toList();
        }
        if (tier.isEmpty()) {
            tier = usable;
        }
        return tier.get(random.applyAsInt(tier.size()));
    }
}
