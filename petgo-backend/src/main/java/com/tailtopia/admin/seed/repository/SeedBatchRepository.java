package com.tailtopia.admin.seed.repository;

import com.tailtopia.admin.seed.domain.SeedBatch;
import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;

/** 批次容器仓储（V1.1.6 Story 13.1）。 */
public interface SeedBatchRepository extends JpaRepository<SeedBatch, Long> {

    List<SeedBatch> findTop50ByOrderByIdDesc();
}
