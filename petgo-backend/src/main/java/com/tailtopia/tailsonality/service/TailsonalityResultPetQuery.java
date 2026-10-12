package com.tailtopia.tailsonality.service;

import com.tailtopia.tailsonality.domain.TailsonalityResult;
import com.tailtopia.tailsonality.repository.TailsonalityResultRepository;
import java.util.Optional;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 结果 → 所属宠物 id 的只读口（V1.3.4 Story 4.2）。只依赖自己的仓库（profile 注入它，避免构造器循环；
 * 同 {@link TailsonalityBadgeQuery}）。
 */
@Service
public class TailsonalityResultPetQuery {

    private final TailsonalityResultRepository results;

    public TailsonalityResultPetQuery(TailsonalityResultRepository results) {
        this.results = results;
    }

    /** 结果行所属宠物；结果不存在（如已随删档物理删除）→ empty。 */
    @Transactional(readOnly = true)
    public Optional<Long> petProfileIdOf(long resultId) {
        return results.findById(resultId).map(TailsonalityResult::getPetProfileId);
    }
}
