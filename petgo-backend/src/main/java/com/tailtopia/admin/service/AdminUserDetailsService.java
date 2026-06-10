package com.tailtopia.admin.service;

import com.tailtopia.auth.domain.Role;
import com.tailtopia.auth.domain.User;
import com.tailtopia.auth.repository.UserRepository;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.security.core.userdetails.UsernameNotFoundException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * ADMIN 表单登录的用户加载（Story 3.1）。按 email 精确取 {@code role=ADMIN} 且未注销、已设密码的账号。
 *
 * <p>跨模块只经 {@code UserRepository}（同属 auth 持久层的只读复用，非 content/profile 表）；
 * user/vet/未设密码账号一律不可经此登录后台。
 */
@Service
public class AdminUserDetailsService implements UserDetailsService {

    private final UserRepository users;

    public AdminUserDetailsService(UserRepository users) {
        this.users = users;
    }

    @Override
    @Transactional(readOnly = true)
    public UserDetails loadUserByUsername(String username) throws UsernameNotFoundException {
        User u = users.findByEmailAndRole(username, Role.ADMIN)
                .filter(user -> user.getDeletedAt() == null)
                .filter(user -> user.getPasswordHash() != null)
                .orElseThrow(() -> new UsernameNotFoundException("管理员账号不存在"));
        return new AdminUserDetails(u.getId(), u.getEmail(), u.getPasswordHash());
    }
}
