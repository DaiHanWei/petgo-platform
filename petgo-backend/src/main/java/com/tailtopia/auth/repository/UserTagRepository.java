package com.tailtopia.auth.repository;

import com.tailtopia.auth.domain.UserTag;
import org.springframework.data.jpa.repository.JpaRepository;

public interface UserTagRepository extends JpaRepository<UserTag, Long> {
}
