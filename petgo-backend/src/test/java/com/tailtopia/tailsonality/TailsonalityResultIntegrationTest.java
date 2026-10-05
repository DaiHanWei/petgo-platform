package com.tailtopia.tailsonality;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
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
import com.tailtopia.tailsonality.domain.TailsonalityCatalog;
import java.util.stream.Collectors;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * V1.3.2 batch-a Story 2.1 · L1（需 postgres + redis）：迁移 + validate、提交 / 列表 / 单条、删档级联（AC3–AC5、AC7.2）。
 */
class TailsonalityResultIntegrationTest extends ApiIntegrationTest {

    private static final String BASE = "/api/v1/pet-profiles/me/tailsonality/results";

    @Autowired
    private PetProfileRepository petProfiles;
    @Autowired
    private ProfileDeletionService profileDeletion;
    @Autowired
    private JdbcTemplate jdbc;
    @Autowired
    private VetTestSupport vets;
    @Autowired
    private AccountDeletionRepository deletions;
    @Autowired
    private AccountDeletionService accountDeletion;

    private static String body(int idx) {
        return TailsonalityCatalog.QUESTION_IDS.stream().map(q -> "\"" + q + "\":" + idx)
                .collect(Collectors.joining(",", "{", "}"));
    }

    private PetProfile pet(User u, PetType type) {
        return petProfiles.save(PetProfile.create(u.getId(), type, "Momo", null, null, null, null,
                "TOK-" + SEQ.incrementAndGet()));
    }

    private String submit(User u, String body, int expect) throws Exception {
        return mvc.perform(post(BASE).header("Authorization", userBearer(u.getId()))
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().is(expect)).andReturn().getResponse().getContentAsString();
    }

    @Test
    void submitListGetAndRetake() throws Exception {
        User u = newUser();
        pet(u, PetType.OTHER);
        String first = submit(u, body(0), 201);
        assertThat(json.readTree(first).get("typeCode").asText()).isEqualTo("ENFJ-H");
        assertThat(json.readTree(first).get("questionSet").asText()).isEqualTo("GENERAL");
        assertThat(first).doesNotContain("answers").doesNotContain("unlockedAt");
        String token = json.readTree(first).get("token").asText();
        submit(u, body(3), 201);

        mvc.perform(get(BASE).header("Authorization", userBearer(u.getId())))
                .andExpect(jsonPath("$.items.length()").value(2))
                .andExpect(jsonPath("$.items[0].typeCode").value("ISTP-L"))
                .andExpect(jsonPath("$.items[0].resultIndex").value(2))
                .andExpect(jsonPath("$.items[1].resultIndex").value(1));
        mvc.perform(get(BASE + "/" + token).header("Authorization", userBearer(u.getId())))
                .andExpect(status().isOk()).andExpect(jsonPath("$.resultIndex").value(1));

        // 他人的 token 与不存在不可区分。
        User stranger = newUser();
        pet(stranger, PetType.CAT);
        mvc.perform(get(BASE + "/" + token).header("Authorization", userBearer(stranger.getId())))
                .andExpect(status().isNotFound());
    }

    @Test
    void validationAndNoPet() throws Exception {
        User noPet = newUser();
        submit(noPet, body(0), 404);
        mvc.perform(get(BASE).header("Authorization", userBearer(noPet.getId()))).andExpect(status().isNotFound());

        User u = newUser();
        pet(u, PetType.DOG);
        submit(u, body(0).replace("\"P3\":0", "\"P3\":4"), 422);
        submit(u, body(0).replace("}", ",\"questionSet\":\"CAT\"}"), 422);
        mvc.perform(get(BASE).header("Authorization", userBearer(u.getId())))
                .andExpect(jsonPath("$.items.length()").value(0));
    }

    @Test
    void profileDeletionRemovesResultsAndRebuiltPetCanSubmit() throws Exception {
        User u = newUser();
        PetProfile p = pet(u, PetType.CAT);
        submit(u, body(0), 201);
        profileDeletion.deleteByUserId(u.getId());
        assertThat(jdbc.queryForObject("SELECT count(*) FROM tailsonality_results WHERE pet_profile_id = ?",
                Long.class, p.getId())).isZero();
        assertThat(jdbc.queryForObject("SELECT count(*) FROM tailsonality_results WHERE user_id = ?",
                Long.class, u.getId())).isZero();

        pet(u, PetType.CAT);
        String again = submit(u, body(0), 201);
        assertThat(json.readTree(again).get("resultIndex").asInt()).isEqualTo(1);
    }

    /** AC5.3（L1 本地验收 2026-10-05 补）：真实 ACTIVE 兽医的 token 访问列表 / 单条均 403（不能落 anyRequest().authenticated()）。 */
    @Test
    void vetTokenIsForbiddenOnBothPaths() throws Exception {
        long vetId = vets.newActiveVet("tailsonality-it").getId();
        mvc.perform(get(BASE).header("Authorization", vetBearer(vetId))).andExpect(status().isForbidden());
        mvc.perform(get(BASE + "/someToken").header("Authorization", vetBearer(vetId))).andExpect(status().isForbidden());
    }

    /** AC7.3（L1 本地验收 2026-10-05 补）：注销全链路 AccountDeletionService.execute 后该用户无结果行。 */
    @Test
    void accountDeletionRemovesResults() throws Exception {
        User u = newUser();
        pet(u, PetType.DOG);
        submit(u, body(0), 201);
        submit(u, body(3), 201);
        AccountDeletion d = deletions.save(AccountDeletion.request(u.getId()));
        accountDeletion.execute(d.getId());
        assertThat(jdbc.queryForObject("SELECT count(*) FROM tailsonality_results WHERE user_id = ?",
                Long.class, u.getId())).isZero();
    }
}
