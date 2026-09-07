package com.tailtopia.admin.seed.dto;

import com.tailtopia.admin.seed.domain.SeedBatchRow;
import java.util.List;

/**
 * 一行的校验结果（V1.1.6 Story 13.4 · AC1/AC3）。
 *
 * <p><b>现状最严重的一点</b>：此前**没有校验预览、没有确认入库，提交即上线** ——
 * 50 行错 3 行就是 3 条线上真帖，只能逐条去找、逐条下架。
 *
 * @param errors    硬错误。非空 ⇒ 该行**不发**，留在草稿态可改后重提。
 *                  🛡 <b>不阻塞整批</b>（AC2）。
 * @param duplicate 去重命中。🔴 <b>这是提示不是错误</b>（AC4）——
 *                  由运营决定是否仍要发布。原先是**静默跳过**，界面只显示一个跳过条数，
 *                  运营根本不知道哪一条被吞了。
 */
public record RowValidation(SeedBatchRow row, List<String> errors, boolean duplicate) {

    public boolean passes() {
        return errors.isEmpty();
    }

    /** 能发但值得看一眼。 */
    public boolean warns() {
        return errors.isEmpty() && duplicate;
    }
}
