package com.tailtopia.content.larksync;

import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

public interface LarkContentPublishRepository extends JpaRepository<LarkContentPublish, Long> {

    Optional<LarkContentPublish> findByContentCode(String contentCode);
}
