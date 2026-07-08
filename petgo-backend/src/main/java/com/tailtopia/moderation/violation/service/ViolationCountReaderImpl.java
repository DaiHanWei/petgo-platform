package com.tailtopia.moderation.violation.service;

import com.tailtopia.admin.moderation.read.ViolationCountReader;
import com.tailtopia.admin.moderation.read.ViolationType;
import com.tailtopia.moderation.violation.repository.ViolationCountRepository;
import java.util.EnumMap;
import java.util.Map;
import org.springframework.context.annotation.Primary;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * {@link ViolationCountReader} 真实现（内容审核 story 9，接上 story 8 的只读端口占位，R1）。
 * 由 {@code violation_counts} 聚合表支撑，供 story 8 运营后台按账号+类型展示累计违规。
 *
 * <p>{@code @Primary}：story 8 的 {@code @ConditionalOnMissingBean} 占位空实现设计上应因本 bean 存在而退场；
 * 加 {@code @Primary} 作稳健兜底——即便条件装配次序未抑制占位，注入点也确定解析到本真实现（避免 NoUniqueBean）。
 * <b>只读，不触发任何自动限制</b>（§5.4）。
 */
@Service
@Primary
public class ViolationCountReaderImpl implements ViolationCountReader {

    private final ViolationCountRepository counts;

    public ViolationCountReaderImpl(ViolationCountRepository counts) {
        this.counts = counts;
    }

    @Override
    @Transactional(readOnly = true)
    public Map<ViolationType, Integer> countsFor(long accountRef) {
        Map<ViolationType, Integer> result = new EnumMap<>(ViolationType.class);
        for (var row : counts.findByAccountId(accountRef)) {
            result.put(row.getViolationType(), row.getViolationCount());
        }
        return result;
    }
}
