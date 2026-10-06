package com.tailtopia.tailsonality.service;

import com.tailtopia.purchase.domain.KeepsakeSku;
import com.tailtopia.purchase.domain.KeepsakeTargetResolver;
import com.tailtopia.tailsonality.domain.TailsonalityResult;
import com.tailtopia.tailsonality.repository.TailsonalityResultRepository;
import java.util.Optional;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/** 订单中心「查看」目标（V1.3.2 Story 3.6）：Tailsonality 结果 token；结果行已删 → empty。 */
@Component
public class TailsonalityTargetResolver implements KeepsakeTargetResolver {

    private final TailsonalityResultRepository results;

    public TailsonalityTargetResolver(TailsonalityResultRepository results) {
        this.results = results;
    }

    @Override
    public KeepsakeSku sku() {
        return KeepsakeSku.TAILSONALITY;
    }

    @Override
    public String targetKind() {
        return "TAILSONALITY_RESULT";
    }

    @Override
    @Transactional(readOnly = true)
    public Optional<String> targetToken(long refId) {
        return results.findById(refId).map(TailsonalityResult::getPublicToken);
    }
}
