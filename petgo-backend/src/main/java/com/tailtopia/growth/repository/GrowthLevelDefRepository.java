package com.tailtopia.growth.repository;

import com.tailtopia.growth.domain.GrowthLevelDef;
import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;

/** 等级表仓储（V1.4.0 ① Story 1.1）。 */
public interface GrowthLevelDefRepository extends JpaRepository<GrowthLevelDef, Integer> {

    List<GrowthLevelDef> findAllByOrderByLevelAsc();
}
