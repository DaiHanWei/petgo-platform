package com.tailtopia.consult.service;

import com.tailtopia.consult.domain.ConsultRating;
import com.tailtopia.consult.domain.ConsultSession;
import com.tailtopia.consult.repository.ConsultRatingRepository;
import com.tailtopia.consult.repository.ConsultSessionRepository;
import com.tailtopia.shared.media.PersonalMedia;
import java.util.ArrayList;
import java.util.List;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * consult 模块注销匿名化（Story 7.3，决策 D1）：会话/评分<b>保留行</b>，剥 user PII（解关联 user_id +
 * 清症状/私密图引用），保留 danger_level/vet_id/stars/comment 供运营 FR-33 与未来 FR-5 历史库。
 *
 * <p>consult 私密桶②图（分诊升级图/聊天存档图）属个人 PII，随 account 级联<b>删除</b>（本服务收集 key 返回）。
 * 兽医侧历史（5.8）匿名化后仍可见会话（作者显示「已注销用户」），不空屏。
 */
@Service
public class ConsultAnonymizationService {

    private final ConsultSessionRepository sessions;
    private final ConsultRatingRepository ratings;

    public ConsultAnonymizationService(ConsultSessionRepository sessions, ConsultRatingRepository ratings) {
        this.sessions = sessions;
        this.ratings = ratings;
    }

    @Transactional
    public PersonalMedia anonymizeByUserId(long userId) {
        List<String> privateKeys = new ArrayList<>();
        for (ConsultSession s : sessions.findByUserId(userId)) {
            if (s.getAiImageRefs() != null) {
                privateKeys.addAll(s.getAiImageRefs());
            }
            s.anonymize(); // 剥 user_id + 症状/图引用，保留 danger_level/vet_id
            sessions.save(s);
        }
        for (ConsultRating r : ratings.findByUserId(userId)) {
            r.anonymize(); // 剥 user_id，保留 stars/comment/vet_id
            ratings.save(r);
        }
        return PersonalMedia.ofPrivate(privateKeys);
    }
}
