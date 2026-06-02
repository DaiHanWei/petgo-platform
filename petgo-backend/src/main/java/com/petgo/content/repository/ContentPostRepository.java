package com.petgo.content.repository;

import com.petgo.content.domain.ContentPost;
import org.springframework.data.jpa.repository.JpaRepository;

public interface ContentPostRepository extends JpaRepository<ContentPost, Long> {
}
