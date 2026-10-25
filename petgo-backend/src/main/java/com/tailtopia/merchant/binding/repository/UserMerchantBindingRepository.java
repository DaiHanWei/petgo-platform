package com.tailtopia.merchant.binding.repository;

import com.tailtopia.merchant.binding.domain.UserMerchantBinding;
import org.springframework.data.jpa.repository.JpaRepository;

public interface UserMerchantBindingRepository extends JpaRepository<UserMerchantBinding, Long> {
}
