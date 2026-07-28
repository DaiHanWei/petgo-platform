package com.tailtopia.consult;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.tailtopia.auth.domain.User;
import com.tailtopia.consult.repository.ConsultRequestRepository;
import com.tailtopia.consult.service.ConsultRequestService;
import com.tailtopia.profile.domain.PetProfile;
import com.tailtopia.profile.domain.PetType;
import com.tailtopia.profile.repository.PetProfileRepository;
import com.tailtopia.profile.service.CardTokenGenerator;
import com.tailtopia.support.ApiIntegrationTest;
import com.tailtopia.support.VetTestSupport;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpHeaders;

/**
 * L1（需 Docker postgres+redis）。Story 3.6 兽医计费队列 {@code GET /api/v1/vet/consultations/queue}。
 *
 * <p>核心：{@code available} = QUEUEING 池（FIFO + 宠物身份富化）；接单后 {@code awaitingPays} 含该单（服务端权威
 * payDeadline）+ 本兽医 {@code available} 空（忙不能再接）；不忙的另一兽医仍见剩余池；非 VET 403、缺 JWT 401。
 */
class VetQueueIntegrationTest extends ApiIntegrationTest {

    @Autowired
    private ConsultRequestService requestService;
    @Autowired
    private ConsultRequestRepository requests;
    @Autowired
    private PetProfileRepository petProfiles;
    @Autowired
    private CardTokenGenerator tokenGenerator;
    @Autowired
    private VetTestSupport vets;

    /** 清空计费请求，隔离兄弟用例/其它测试类遗留的 QUEUEING 行（本类断言依赖精确池大小）。 */
    @BeforeEach
    void cleanRequests() {
        requests.deleteAll();
    }

    /** 建一个带宠物档案的用户并免费入队（QUEUEING），返回 requestToken。 */
    private String enqueue(String petName) {
        User u = newUser();
        petProfiles.save(PetProfile.create(u.getId(), PetType.DOG, petName, null, "柴犬", null, null,
                tokenGenerator.generate()));
        return requestService.createRequest(u.getId()).request().getRequestToken();
    }

    // ---- AC1：available = QUEUEING 池，FIFO + 宠物身份富化 ----

    @Test
    void queueReturnsAvailablePoolWithPetIdentityInFifoOrder() throws Exception {
        String first = enqueue("旺财");
        String second = enqueue("小白");
        long vetId = vets.newActiveVet("看队列医生").getId();

        mvc.perform(get("/api/v1/vet/consultations/queue")
                        .header(HttpHeaders.AUTHORIZATION, vetBearer(vetId)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.awaitingPays.length()").value(0))     // 未接单 → 无待支付项
                .andExpect(jsonPath("$.available.length()").value(2))
                .andExpect(jsonPath("$.available[0].requestToken").value(first))  // FIFO（先入先出）
                .andExpect(jsonPath("$.available[1].requestToken").value(second))
                .andExpect(jsonPath("$.available[0].petName").value("旺财"))       // 身份富化
                .andExpect(jsonPath("$.available[0].queueDeadlineAt").isNotEmpty())
                .andExpect(jsonPath("$.available[0].waitingSeconds").isNumber());
    }

    // ---- AC1/FR-53A + 364：接单后 awaitingPays 含该单，池对本兽医仍可见（无占用互斥，可继续接）----

    @Test
    void afterAcceptAwaitingPaysShownAndPoolStaysVisible() throws Exception {
        String accepted = enqueue("阿黄");
        enqueue("咪咪"); // 池中另留一单
        long vetId = vets.newActiveVet("接单医生").getId();
        requestService.acceptRequest(vetId, accepted);

        // 接单兽医：awaitingPays 含该单（服务端权威 payDeadline + 宠物名）；剩余池仍可见（364：可继续接）。
        mvc.perform(get("/api/v1/vet/consultations/queue")
                        .header(HttpHeaders.AUTHORIZATION, vetBearer(vetId)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.awaitingPays.length()").value(1))
                .andExpect(jsonPath("$.awaitingPays[0].requestToken").value(accepted))
                .andExpect(jsonPath("$.awaitingPays[0].petName").value("阿黄"))
                .andExpect(jsonPath("$.awaitingPays[0].payDeadlineAt").isNotEmpty())
                .andExpect(jsonPath("$.available.length()").value(1))
                .andExpect(jsonPath("$.available[0].petName").value("咪咪"));

        // 另一兽医：无 awaitingPays，同样见剩余池（已接单的不在池里）。
        long otherVet = vets.newActiveVet("旁观医生").getId();
        mvc.perform(get("/api/v1/vet/consultations/queue")
                        .header(HttpHeaders.AUTHORIZATION, vetBearer(otherVet)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.awaitingPays.length()").value(0))
                .andExpect(jsonPath("$.available.length()").value(1))
                .andExpect(jsonPath("$.available[0].petName").value("咪咪"));

        // 364：接单后继续接第二单 → awaitingPays 变 2、池清空。
        String more = enqueue("三单");
        requestService.acceptRequest(vetId, more);
        mvc.perform(get("/api/v1/vet/consultations/queue")
                        .header(HttpHeaders.AUTHORIZATION, vetBearer(vetId)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.awaitingPays.length()").value(2))
                .andExpect(jsonPath("$.available.length()").value(1)); // 咪咪仍在池（未被接）
    }

    // ---- AC1：非 VET 角色 403、缺 JWT 401 ----

    @Test
    void nonVetForbiddenAndAnonymousUnauthorized() throws Exception {
        long userId = newUser().getId();
        mvc.perform(get("/api/v1/vet/consultations/queue")
                        .header(HttpHeaders.AUTHORIZATION, userBearer(userId)))
                .andExpect(status().isForbidden());
        mvc.perform(get("/api/v1/vet/consultations/queue"))
                .andExpect(status().isUnauthorized());
    }
}
