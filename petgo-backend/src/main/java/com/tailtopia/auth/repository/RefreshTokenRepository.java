package com.tailtopia.auth.repository;

import com.tailtopia.auth.domain.RefreshToken;
import com.tailtopia.auth.domain.SubjectType;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.transaction.annotation.Transactional;

public interface RefreshTokenRepository extends JpaRepository<RefreshToken, Long> {

    Optional<RefreshToken> findByTokenHash(String tokenHash);

    /** Story 7.3：注销级联删除该用户全部 refresh 句柄（仅 USER 主体）。 */
    @Transactional
    void deleteByUserIdAndSubjectType(Long userId, SubjectType subjectType);
}
