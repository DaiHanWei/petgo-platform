package com.tailtopia.admin.places.repository;

import com.tailtopia.admin.places.domain.Place;
import com.tailtopia.admin.places.domain.PlaceStatus;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

/** {@code places} 仓储（Story 5.1）。列表查询默认 {@code deleted_at IS NULL}（AC2）。 */
public interface PlaceRepository extends JpaRepository<Place, Long> {

    Optional<Place> findByPublicToken(String publicToken);

    Optional<Place> findByIdAndDeletedAtIsNull(Long id);

    long countByStatusAndDeletedAtIsNull(PlaceStatus status);
}
