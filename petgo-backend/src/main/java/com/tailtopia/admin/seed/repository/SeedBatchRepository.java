package com.tailtopia.admin.seed.repository;

import com.tailtopia.admin.seed.domain.SeedBatch;
import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;

/** 批次容器仓储（V1.1.6 Story 13.1）。 */
public interface SeedBatchRepository extends JpaRepository<SeedBatch, Long> {

    List<SeedBatch> findTop50ByOrderByIdDesc();

    /**
     * 批次列表用（bug 20260826）：**只取已保存过的**。
     * 只点了「新建批次」还没填任何东西的空批次不进列表。
     */
    List<SeedBatch> findTop50BySavedAtIsNotNullOrderByIdDesc();
}
