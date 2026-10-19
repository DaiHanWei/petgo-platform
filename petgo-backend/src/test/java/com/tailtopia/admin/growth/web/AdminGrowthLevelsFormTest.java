package com.tailtopia.admin.growth.web;

import static org.assertj.core.api.Assertions.assertThat;

import com.tailtopia.admin.growth.service.AdminGrowthConfigService.LevelsForm;
import java.util.Map;
import org.junit.jupiter.api.Test;

/** L0：等级表门槛框按千分位显示（UI G5），提交回来先还原成纯数字；不规范写法原样交给服务层 422。 */
class AdminGrowthLevelsFormTest {

    @Test
    void groupedThresholdsAreSubmittedAsPlainDigits() {
        LevelsForm f = AdminGrowthConfigController.levelsForm(Map.of(
                "lv.2", "70", "lv.11", "4,500", "lv.20", " 41,300 ", "lv.x", "9",
                "appendThreshold", "1,000,000", "appendTier", "STAR"));
        assertThat(f.thresholds()).containsEntry(2, "70").containsEntry(11, "4500").containsEntry(20, "41300")
                .hasSize(3);
        assertThat(f.appendThreshold()).isEqualTo("1000000");
        assertThat(f.appendTier()).isEqualTo("STAR");
    }

    @Test
    void malformedGroupingIsLeftForServiceValidation() {
        assertThat(AdminGrowthConfigController.plainDigits("1,2,3")).isEqualTo("1,2,3");
        assertThat(AdminGrowthConfigController.plainDigits("45,00")).isEqualTo("45,00");
        assertThat(AdminGrowthConfigController.plainDigits("")).isEmpty();
        assertThat(AdminGrowthConfigController.plainDigits(null)).isNull();
        assertThat(AdminGrowthConfigController.levelsForm(Map.of()).appendThreshold()).isNull();
    }
}
