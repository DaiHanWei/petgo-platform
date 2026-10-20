package com.tailtopia.profile;

import static org.assertj.core.api.Assertions.assertThat;

import com.tailtopia.auth.domain.User;
import com.tailtopia.profile.support.MultiPetDbFixture;
import com.tailtopia.support.ApiIntegrationTest;
import java.time.Instant;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;

/** L1：V1.4.0 multi-pet Story 1.4 · AC3 / AC7.2 —— 迁移 1603 后 schema 状态；同一 owner 直插两行成功。 */
class PetSchemaMigrationIntegrationTest extends ApiIntegrationTest {

    @Autowired
    private JdbcTemplate jdbc;

    @Test
    void ownerUniqueGoneAndIndexPresent() {
        assertThat(jdbc.queryForObject(
                "SELECT count(*) FROM pg_constraint WHERE conname = 'uq_pet_profiles_owner_id'", Integer.class))
                .isZero();
        assertThat(jdbc.queryForObject(
                "SELECT count(*) FROM pg_indexes WHERE tablename = 'pet_profiles'"
                        + " AND indexname = 'idx_pet_profiles_owner_created'", Integer.class))
                .isEqualTo(1);
        assertThat(jdbc.queryForObject(
                "SELECT count(*) FROM pg_constraint WHERE conname = 'uq_pet_profiles_card_token'", Integer.class))
                .isEqualTo(1);
    }

    @Test
    void sameOwnerCanHoldTwoRowsAndFixtureIsNoOp() {
        assertThat(MultiPetDbFixture.dropOwnerUniqueIfPresent(jdbc)).isFalse();
        User owner = newUser();
        MultiPetDbFixture.insertPet(jdbc, owner.getId(), "A", "CAT", Instant.now().minusSeconds(60));
        MultiPetDbFixture.insertPet(jdbc, owner.getId(), "B", "DOG", Instant.now());
        assertThat(jdbc.queryForObject("SELECT count(*) FROM pet_profiles WHERE owner_id = ?", Integer.class,
                owner.getId())).isEqualTo(2);
    }
}
