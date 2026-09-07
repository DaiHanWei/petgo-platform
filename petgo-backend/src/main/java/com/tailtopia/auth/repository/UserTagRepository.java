package com.tailtopia.auth.repository;

import com.tailtopia.auth.domain.UserTag;
import org.springframework.data.jpa.repository.JpaRepository;

public interface UserTagRepository extends JpaRepository<UserTag, Long> {

    /** 后台列表（Story 11.3）：全部标签（含已下线），新建在前。 */
    java.util.List<UserTag> findAllByOrderByIdDesc();

    /** 分配下拉：仅在线标签。 */
    java.util.List<UserTag> findByRetiredAtIsNullOrderByIdDesc();

    java.util.Optional<UserTag> findByCode(String code);
}
