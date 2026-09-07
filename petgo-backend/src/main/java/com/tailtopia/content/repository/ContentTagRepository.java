package com.tailtopia.content.repository;

import com.tailtopia.content.domain.ContentTag;
import org.springframework.data.jpa.repository.JpaRepository;

public interface ContentTagRepository extends JpaRepository<ContentTag, Long> {

    /** 后台列表（Story 11.2）：全部标签（含已下线），新建在前。 */
    java.util.List<ContentTag> findAllByOrderByIdDesc();

    /** 打标下拉：仅在线标签。 */
    java.util.List<ContentTag> findByRetiredAtIsNullOrderByIdDesc();

    java.util.Optional<ContentTag> findByCode(String code);
}
