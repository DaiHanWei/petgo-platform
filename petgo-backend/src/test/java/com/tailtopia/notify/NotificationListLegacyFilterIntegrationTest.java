package com.tailtopia.notify;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.tailtopia.auth.domain.User;
import com.tailtopia.notify.domain.NotificationType;
import com.tailtopia.notify.service.NotificationService;
import com.tailtopia.profile.service.ClientAppVersion;
import com.tailtopia.support.ApiIntegrationTest;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpHeaders;

/** L1（V1.5.0 Story 2.4 · AC4）：旧版客户端的通知列表在 SQL 层排除 HEALTH_*；1.5.0 照常返回。 */
class NotificationListLegacyFilterIntegrationTest extends ApiIntegrationTest {

    @Autowired
    private NotificationService notifications;

    @Test
    void oldClientsDoNotSeeHealthTypes() throws Exception {
        User u = newUser();
        notifications.sendWithoutPush(u.getId(), NotificationType.HEALTH_CHECKUP, "t", "b",
                NotificationType.HEALTH_CHECKUP.name(), "tok");
        mvc.perform(get("/api/v1/notifications").header(HttpHeaders.AUTHORIZATION, userBearer(u.getId())))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items.length()").value(0));
        mvc.perform(get("/api/v1/notifications").header(HttpHeaders.AUTHORIZATION, userBearer(u.getId()))
                        .header(ClientAppVersion.HEADER, "1.5.0"))
                .andExpect(jsonPath("$.items.length()").value(1));
    }
}
