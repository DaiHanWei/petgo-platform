package com.tailtopia.admin.places.repository;

import com.tailtopia.admin.places.domain.PlaceCheckin;
import org.springframework.data.jpa.repository.JpaRepository;

/** {@code place_checkins} 仓储（Story 5.1）。 */
public interface PlaceCheckinRepository extends JpaRepository<PlaceCheckin, Long> {

    long countByPlaceId(Long placeId);
}
