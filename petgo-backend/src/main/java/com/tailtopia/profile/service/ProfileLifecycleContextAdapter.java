package com.tailtopia.profile.service;

import com.tailtopia.notify.port.LifecycleContextPort;
import com.tailtopia.profile.domain.PetProfile;
import com.tailtopia.profile.repository.PetProfileRepository;
import com.tailtopia.profile.repository.UserNewbieMarkDao;
import java.time.LocalDate;
import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * {@link LifecycleContextPort} 的 profile 侧实现（V1.5.0 onboarding-recall Story 6.1 · OR-21）：四次批量查询
 * （宠物 / 新手标记 / 今日活跃 / Lulus flag），与候选数无关。默认宠物只经 {@link DefaultPetPolicy}。
 */
@Component
public class ProfileLifecycleContextAdapter implements LifecycleContextPort {

    private final PetProfileRepository pets;
    private final UserNewbieMarkDao newbieMarks;
    private final NamedParameterJdbcTemplate jdbc;

    public ProfileLifecycleContextAdapter(PetProfileRepository pets, UserNewbieMarkDao newbieMarks,
            NamedParameterJdbcTemplate jdbc) {
        this.pets = pets;
        this.newbieMarks = newbieMarks;
        this.jdbc = jdbc;
    }

    @Override
    @Transactional(readOnly = true)
    public LifecycleContext contextOf(Collection<Long> userIds, LocalDate wibToday) {
        if (userIds == null || userIds.isEmpty()) {
            return LifecycleContext.EMPTY;
        }
        List<Long> ids = userIds.stream().distinct().toList();
        Map<Long, DefaultPet> defaults = new HashMap<>();
        DefaultPetPolicy.pickPerOwner(pets.findAllByOwnerIdInOrderByCreatedAtAscIdAsc(ids))
                .forEach((owner, p) -> defaults.put(owner, toDefaultPet(p)));
        MapSqlParameterSource params = new MapSqlParameterSource("ids", ids).addValue("day", wibToday);
        Set<Long> activeToday = new HashSet<>(jdbc.queryForList(
                "SELECT user_id FROM user_active_days WHERE active_date = :day AND user_id IN (:ids)",
                params, Long.class));
        Set<Long> graduated = new HashSet<>(jdbc.queryForList(
                "SELECT user_id FROM user_milestone_flags WHERE flag_key = 'LULUS_PEMULA' AND user_id IN (:ids)",
                params, Long.class));
        return new LifecycleContext(defaults, newbieMarks.keysOfUsers(ids), activeToday, graduated);
    }

    private static DefaultPet toDefaultPet(PetProfile p) {
        return new DefaultPet(p.getName() == null ? "" : p.getName(), p.getBreed(),
                p.getPetType() == null ? null : p.getPetType().name());
    }
}
