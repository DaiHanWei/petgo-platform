package com.petgo.vet.repository;

import com.petgo.vet.domain.VetAccount;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

/**
 * 兽医账号持久层（Story 5.1）。模块边界：仅 {@code vet.service} 直接访问；
 * Admin slice 经 {@code VetAccountService}，不跨模块直访本 repository。
 */
public interface VetAccountRepository extends JpaRepository<VetAccount, Long> {

    Optional<VetAccount> findByUsername(String username);

    boolean existsByUsername(String username);
}
