package com.tailtopia.moderation.violation.service;

import com.tailtopia.admin.moderation.read.ViolationType;
import com.tailtopia.moderation.violation.repository.ViolationCountRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 账号违规计数累加/清理单一收口（内容审核 story 9，§5.2）。所有<b>人工判定</b>违规处置点经此累加。
 *
 * <p><b>核心边界（务必贯穿）：仅记录、不处置。</b> {@link #record} 只写 {@code violation_counts}——
 * 无事件、无通知、无风控回调、不读回判定链（"仅记录不处置"的物理保证，AC-9）。达任何计数值都不触发自动动作。
 *
 * <p><b>同事务累加</b>：{@code @Transactional}（默认 REQUIRED）→ 加入调用方处置的事务；处置回滚则计数不落、
 * 处置提交则计数必落（原子一致，AC-6）。<b>不新起事务、不 @Async、不引 MQ</b>（护栏）。
 */
@Service
public class ViolationCountService {

    private final ViolationCountRepository counts;

    public ViolationCountService(ViolationCountRepository counts) {
        this.counts = counts;
    }

    /**
     * 累加一次人工判定违规（§5.2）。原子 UPSERT，与调用方处置状态迁移同事务。
     * <b>仅计入人工判定</b>——自动同步拦截（内容从未发布）不得调用本方法（§5.1，AC-7）。
     *
     * @param accountId 违规内容所属 App 用户 id（users.id）
     * @param type      违规类型（POST/COMMENT/NAME/AVATAR）
     */
    @Transactional
    public void record(long accountId, ViolationType type) {
        counts.upsertIncrement(accountId, type.name());
    }

    /** 注销级联删除该账号全部计数行（§5.5 D1/D2；在 user 行删除前调用，无 FK 但保次序）。 */
    @Transactional
    public int deleteByAccount(long accountId) {
        return counts.deleteByAccountId(accountId);
    }
}
