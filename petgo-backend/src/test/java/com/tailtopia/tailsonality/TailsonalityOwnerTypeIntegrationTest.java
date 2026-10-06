package com.tailtopia.tailsonality;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.tailtopia.account.domain.AccountDeletion;
import com.tailtopia.account.repository.AccountDeletionRepository;
import com.tailtopia.account.service.AccountDeletionService;
import com.tailtopia.auth.domain.User;
import com.tailtopia.profile.domain.PetProfile;
import com.tailtopia.profile.domain.PetType;
import com.tailtopia.profile.repository.PetProfileRepository;
import com.tailtopia.profile.service.ProfileDeletionService;
import com.tailtopia.support.ApiIntegrationTest;
import com.tailtopia.support.VetTestSupport;
import com.tailtopia.tailsonality.service.TailsonalityDeletionService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;

/** V1.3.2 batch-a Story 2.5 · L1（需 postgres + redis）：主人类型 GET / PUT、覆盖、校验、删档不删、注销删。 */
class TailsonalityOwnerTypeIntegrationTest extends ApiIntegrationTest {

    private static final String PATH = "/api/v1/me/tailsonality/owner-type";

    @Autowired
    private PetProfileRepository petProfiles;
    @Autowired
    private ProfileDeletionService profileDeletion;
    @Autowired
    private TailsonalityDeletionService tailsonalityDeletion;
    @Autowired
    private JdbcTemplate jdbc;
    @Autowired
    private VetTestSupport vets;
    @Autowired
    private AccountDeletionRepository deletions;
    @Autowired
    private AccountDeletionService accountDeletion;

    private void putType(User u, String code, int expect) throws Exception {
        mvc.perform(put(PATH).header("Authorization", userBearer(u.getId()))
                        .contentType(MediaType.APPLICATION_JSON).content("{\"typeCode\":\"" + code + "\"}"))
                .andExpect(status().is(expect));
    }

    @Test
    void unsetIsEmptyThenSetThenOverwrite() throws Exception {
        User u = newUser();
        mvc.perform(get(PATH).header("Authorization", userBearer(u.getId())))
                .andExpect(status().isOk()).andExpect(content().json("{}"));
        putType(u, "INFP", 200);
        mvc.perform(get(PATH).header("Authorization", userBearer(u.getId())))
                .andExpect(jsonPath("$.typeCode").value("INFP"));
        putType(u, "ENTJ", 200);
        mvc.perform(get(PATH).header("Authorization", userBearer(u.getId())))
                .andExpect(jsonPath("$.typeCode").value("ENTJ"));
        assertThat(jdbc.queryForObject("SELECT count(*) FROM tailsonality_owner_types WHERE user_id = ?",
                Long.class, u.getId())).isOne();
    }

    @Test
    void invalidCodesAre422() throws Exception {
        User u = newUser();
        putType(u, "INFP-H", 422);
        putType(u, "infp", 422);
        putType(u, "XXXX", 422);
    }

    @Test
    void profileDeletionKeepsOwnerTypeButAccountDeletionRemovesIt() throws Exception {
        User u = newUser();
        petProfiles.save(PetProfile.create(u.getId(), PetType.CAT, "Momo", null, null, null, null,
                "TOK-" + SEQ.incrementAndGet()));
        putType(u, "ISFJ", 200);
        profileDeletion.deleteByUserId(u.getId());
        assertThat(jdbc.queryForObject("SELECT count(*) FROM tailsonality_owner_types WHERE user_id = ?",
                Long.class, u.getId())).as("删档不删主人类型（账号级，AD-17）").isOne();
        tailsonalityDeletion.deleteOwnerTypeByUserId(u.getId());
        assertThat(jdbc.queryForObject("SELECT count(*) FROM tailsonality_owner_types WHERE user_id = ?",
                Long.class, u.getId())).isZero();
    }

    /** AC1.4（L1 本地验收 2026-10-05 补）：真实 ACTIVE 兽医 token 访问 GET / PUT 均 403。 */
    @Test
    void vetTokenIsForbidden() throws Exception {
        long vetId = vets.newActiveVet("owner-type-it").getId();
        mvc.perform(get(PATH).header("Authorization", vetBearer(vetId))).andExpect(status().isForbidden());
        mvc.perform(put(PATH).header("Authorization", vetBearer(vetId))
                        .contentType(MediaType.APPLICATION_JSON).content("{\"typeCode\":\"INFP\"}"))
                .andExpect(status().isForbidden());
    }

    /** AC2.3（L1 本地验收 2026-10-05 补）：AccountDeletionService.execute 注销全链路后无该用户行。 */
    @Test
    void accountDeletionFullChainRemovesOwnerType() throws Exception {
        User u = newUser();
        putType(u, "ENTP", 200);
        AccountDeletion d = deletions.save(AccountDeletion.request(u.getId()));
        accountDeletion.execute(d.getId());
        assertThat(jdbc.queryForObject("SELECT count(*) FROM tailsonality_owner_types WHERE user_id = ?",
                Long.class, u.getId())).isZero();
    }
}
