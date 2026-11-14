package com.tailtopia.content.autolike;

import com.tailtopia.auth.domain.AccountType;
import com.tailtopia.auth.domain.User;
import com.tailtopia.auth.domain.UserStatus;
import com.tailtopia.auth.repository.UserRepository;
import java.util.List;
import org.springframework.stereotype.Component;

/**
 * 自动点赞的虚拟号池（V1.5.0 FR-216）：VIRTUAL + enabled + 未注销 + {@code status == ACTIVE}。
 * 比自动评论的池多一条 ACTIVE（自动评论的池不动，PRD §6.2）。计划与派发共用。
 */
@Component
public class AutoLikeVirtualPool {

    private final UserRepository users;

    public AutoLikeVirtualPool(UserRepository users) {
        this.users = users;
    }

    public List<User> load() {
        return users.findByAccountTypeOrderByIdDesc(AccountType.VIRTUAL).stream()
                .filter(User::isEnabled)
                .filter(u -> u.getDeletedAt() == null)
                .filter(u -> u.getStatus() == UserStatus.ACTIVE)
                .toList();
    }
}
