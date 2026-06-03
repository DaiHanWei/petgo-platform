package com.petgo.auth.repository;

import com.petgo.auth.domain.RefreshToken;
import com.petgo.auth.domain.SubjectType;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.transaction.annotation.Transactional;

public interface RefreshTokenRepository extends JpaRepository<RefreshToken, Long> {

    Optional<RefreshToken> findByTokenHash(String tokenHash);

    /** Story 7.3：注销级联删除该用户全部 refresh 句柄（仅 USER 主体）。 */
    @Transactional
    void deleteByUserIdAndSubjectType(Long userId, SubjectType subjectType);
}
