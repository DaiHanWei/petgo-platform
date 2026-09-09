package com.tailtopia.admin.places.repository;

import com.tailtopia.admin.places.domain.PlacePhoto;
import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;

/** {@code place_photos} 仓储（Story 5.1）。 */
public interface PlacePhotoRepository extends JpaRepository<PlacePhoto, Long> {

    List<PlacePhoto> findByPlaceIdAndDeletedAtIsNullOrderByCreatedAtAsc(Long placeId);

    long countByPlaceIdAndDeletedAtIsNull(Long placeId);
}
