package com.tailtopia.auth.repository;

import com.tailtopia.auth.domain.Role;
import com.tailtopia.auth.domain.User;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

public interface UserRepository extends JpaRepository<User, Long> {

    Optional<User> findByGoogleSub(String googleSub);

    /** Story 3.1：ADMIN 账密登录按 email + role 精确匹配。 */
    Optional<User> findByEmailAndRole(String email, Role role);
}
