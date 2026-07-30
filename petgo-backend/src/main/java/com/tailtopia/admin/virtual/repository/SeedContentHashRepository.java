package com.tailtopia.admin.virtual.repository;

import com.tailtopia.admin.virtual.domain.SeedContentHash;
import org.springframework.data.jpa.repository.JpaRepository;

/** 种子内容去重仓储（Story 9.8 Part 2）。 */
public interface SeedContentHashRepository extends JpaRepository<SeedContentHash, String> {
}
