package com.tailtopia.social.read;

import com.tailtopia.social.repository.PetFollowRepository;
import java.util.Collection;
import java.util.HashSet;
import java.util.Set;
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
}
