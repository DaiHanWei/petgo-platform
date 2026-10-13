package com.tailtopia.social.read;

import com.tailtopia.social.domain.UserFollow;
import com.tailtopia.social.repository.UserFollowRepository;
import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** {@link FollowReader} 实现（V1.3.4 follow · Story 1.1）。每次查库，不引缓存。 */
@Service
public class FollowReaderImpl implements FollowReader {

    private final UserFollowRepository follows;

    public FollowReaderImpl(UserFollowRepository follows) {
        this.follows = follows;
    }

    @Override
    @Transactional(readOnly = true)
    public FollowState followState(long viewerId, long targetId) {
        return followStates(viewerId, List.of(targetId)).get(targetId);
    }

    @Override
    @Transactional(readOnly = true)
    public Map<Long, FollowState> followStates(long viewerId, Collection<Long> targetIds) {
        if (targetIds == null || targetIds.isEmpty()) {
            // 🛡 空表短路：`IN ()` 在 Postgres 上是语法错误。
            return Map.of();
        }
        List<Long> ids = targetIds.stream().distinct().toList();
        Set<Long> iFollow = new HashSet<>();
        Set<Long> followsMe = new HashSet<>();
        for (UserFollow f : follows.findBetween(viewerId, ids)) {
            if (f.getFollowerId() == viewerId) {
                iFollow.add(f.getFolloweeId());
            } else {
                followsMe.add(f.getFollowerId());
            }
        }
        Map<Long, FollowState> out = new HashMap<>(ids.size() * 2);
        for (Long id : ids) {
            boolean a = iFollow.contains(id);
            boolean b = followsMe.contains(id);
            out.put(id, a || b ? new FollowState(a, b) : FollowState.NONE);
        }
        return out;
    }
}
