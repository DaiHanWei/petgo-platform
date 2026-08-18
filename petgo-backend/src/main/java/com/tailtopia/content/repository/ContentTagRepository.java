package com.tailtopia.content.repository;

import com.tailtopia.content.domain.ContentTag;
import org.springframework.data.jpa.repository.JpaRepository;

public interface ContentTagRepository extends JpaRepository<ContentTag, Long> {
}
