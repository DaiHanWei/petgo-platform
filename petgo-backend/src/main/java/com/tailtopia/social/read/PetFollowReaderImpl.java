package com.tailtopia.social.read;

import com.tailtopia.social.repository.PetFollowRepository;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** {@link PetFollowReader} 实现（V1.4.2 social-topic · Story 1.1）。每次查库，不引缓存。 */
@Service
public class PetFollowReaderImpl implements PetFollowReader {

    private final PetFollowRepository petFollows;

    public PetFollowReaderImpl(PetFollowRepository petFollows) {
        this.petFollows = petFollows;
    }

    @Override
    @Transactional(readOnly = true)
    public boolean isFollowing(long viewerId, long petId) {
        return petFollows.existsByFollowerIdAndPetProfileId(viewerId, petId);
    }

    @Override
    @Transactional(readOnly = true)
    public Set<Long> followedAmong(long viewerId, Collection<Long> petIds) {
        if (petIds == null || petIds.isEmpty()) {
            // 🛡 空表短路：`IN ()` 在 Postgres 上是语法错误。
            return Set.of();
        }
        return new HashSet<>(petFollows.findFollowedPetIdsAmong(viewerId, petIds.stream().distinct().toList()));
    }

    @Override
    @Transactional(readOnly = true)
    public long countFollowingOf(long userId) {
        return petFollows.countByFollowerId(userId);
    }

    @Override
    @Transactional(readOnly = true)
    public Map<Long, List<Long>> followersOfAny(Collection<Long> petIds) {
        if (petIds == null || petIds.isEmpty()) {
            return Map.of(); // 🛡 `IN ()` 在 Postgres 上是语法错误。
        }
        List<Long> order = petIds.stream().distinct().toList();
        Map<Long, List<Long>> out = new TreeMap<>();
        for (Object[] row : petFollows.findFollowerPetPairsAmong(order)) {
            long follower = ((Number) row[0]).longValue();
            long pet = ((Number) row[1]).longValue();
            out.computeIfAbsent(follower, k -> new ArrayList<>()).add(pet);
        }
        // 每人命中的宠物按入参顺序（宠物创建顺序）—— 一帖多宠时文案取第一只
        Map<Long, Integer> rank = new HashMap<>();
        for (int i = 0; i < order.size(); i++) {
            rank.put(order.get(i), i);
        }
        out.values().forEach(l -> l.sort(Comparator.comparingInt(rank::get)));
        return out;
    }
}
