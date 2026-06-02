package com.petgo.consult.service;

import com.petgo.consult.dto.VetRatingsView;
import com.petgo.consult.repository.ConsultRatingRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 兽医评分查询（Story 5.6，AC4）。供 Admin slice 经 service 接口聚合（不跨 repository）。
 * <b>仅运营可见</b>——用户/兽医侧 API 不暴露兽医公开均分。
 */
@Service
public class ConsultRatingQueryService {

    private final ConsultRatingRepository ratings;

    public ConsultRatingQueryService(ConsultRatingRepository ratings) {
        this.ratings = ratings;
    }

    @Transactional(readOnly = true)
    public VetRatingsView forVet(long vetId) {
        return VetRatingsView.of(vetId, ratings.findByVetIdOrderByCreatedAtDesc(vetId));
    }
}
