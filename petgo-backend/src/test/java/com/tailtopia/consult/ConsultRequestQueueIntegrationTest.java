package com.tailtopia.consult;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.tailtopia.auth.domain.User;
import com.tailtopia.consult.domain.ConsultRequest;
import com.tailtopia.consult.domain.ConsultRequestState;
import com.tailtopia.consult.repository.ConsultOrderRepository;
import com.tailtopia.consult.repository.ConsultRequestRepository;
import com.tailtopia.consult.service.ConsultRequestService;
import com.tailtopia.profile.domain.PetProfile;
import com.tailtopia.profile.domain.PetType;
import com.tailtopia.profile.repository.PetProfileRepository;
import com.tailtopia.profile.service.CardTokenGenerator;
import com.tailtopia.support.ApiIntegrationTest;
import java.time.Duration;
import java.time.Instant;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpHeaders;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * L1（需 Docker）。Story 3.2 免费入队与超时删除。启动即验 V66 契约。
 *
 * <p>核心：{@code POST /consultations} 建 QUEUEING <b>不建订单</b>（A-5）；占用命中不重复建；入队超时物理删无痕；
 * 已接单（ACCEPTED_AWAIT_PAY）不被队列扫描删（state 谓词保护）。
 */
class ConsultRequestQueueIntegrationTest extends ApiIntegrationTest {

    @Autowired
    private ConsultRequestRepository requests;
    @Autowired
    private ConsultOrderRepository orders;
    @Autowired
    private ConsultRequestService requestService;
    @Autowired
    private PetProfileRepository petProfiles;
    @Autowired
    private CardTokenGenerator tokenGenerator;
    @Autowired
    private PlatformTransactionManager txManager;

    private long userWithPet() {
        User u = newUser();
        petProfiles.save(PetProfile.create(u.getId(), PetType.DOG, "旺财", null, "柴犬", null, null,
                tokenGenerator.generate()));
        return u.getId();
    }

    @Test
    void createQueuesWithoutOrder() throws Exception {
        long userId = userWithPet();
        long ordersBefore = orders.count();

        mvc.perform(post("/api/v1/consultations")
                        .header(HttpHeaders.AUTHORIZATION, userBearer(userId)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.state").value("QUEUEING"))
                .andExpect(jsonPath("$.requestToken").isNotEmpty())
                .andExpect(jsonPath("$.alreadyActive").value(false));

        assertThat(requests.existsByUserId(userId)).isTrue();
        assertThat(orders.count()).isEqualTo(ordersBefore); // 未扣费不建单（A-5）
    }

    @Test
    void secondCreateHitsOccupancy() throws Exception {
        long userId = userWithPet();
        mvc.perform(post("/api/v1/consultations").header(HttpHeaders.AUTHORIZATION, userBearer(userId)))
                .andExpect(status().isOk());
        // 第二次发起 → 命中已有 live 请求，不新建第二行。
        mvc.perform(post("/api/v1/consultations").header(HttpHeaders.AUTHORIZATION, userBearer(userId)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.alreadyActive").value(true));
        assertThat(requests.findFirstByUserIdOrderByCreatedAtAsc(userId)).isPresent();
    }

    @Test
    void noPetProfileConflict() throws Exception {
        long userId = newUser().getId(); // 无宠物档案
        mvc.perform(post("/api/v1/consultations").header(HttpHeaders.AUTHORIZATION, userBearer(userId)))
                .andExpect(status().isConflict());
    }

    @Test
    void noJwtUnauthorized() throws Exception {
        mvc.perform(post("/api/v1/consultations")).andExpect(status().isUnauthorized());
    }

    @Test
    void purgeDeletesExpiredQueueingKeepsAcceptedAndFuture() {
        long userId = newUser().getId();
        // 过期 QUEUEING → 应删。
        ConsultRequest expired = requests.save(ConsultRequest.queue(userId, 1L,
                tokenGenerator.generate(), Instant.now().minus(Duration.ofMinutes(2))));
        // 未过期 QUEUEING → 不删（deadline 谓词）。
        ConsultRequest future = requests.save(ConsultRequest.queue(newUser().getId(), 1L,
                tokenGenerator.generate(), Instant.now().plus(Duration.ofMinutes(5))));
        // 过期但已接单 ACCEPTED_AWAIT_PAY → 不删（state 谓词）。
        ConsultRequest accepted = requests.save(ConsultRequest.queue(newUser().getId(), 1L,
                tokenGenerator.generate(), Instant.now().minus(Duration.ofMinutes(2))));
        new TransactionTemplate(txManager).execute(s ->
                requests.tryAccept(accepted.getId(), 999L, Instant.now().plus(Duration.ofSeconds(90))));

        int purged = requestService.purgeExpiredQueue();

        assertThat(purged).isGreaterThanOrEqualTo(1);
        assertThat(requests.findById(expired.getId())).isEmpty();        // 过期 QUEUEING 删
        assertThat(requests.findById(future.getId())).isPresent();       // 未过期不删
        assertThat(requests.findById(accepted.getId())).isPresent();     // 已接单不删
        assertThat(requests.findById(accepted.getId()).orElseThrow().getState())
                .isEqualTo(ConsultRequestState.ACCEPTED_AWAIT_PAY);
    }
}
