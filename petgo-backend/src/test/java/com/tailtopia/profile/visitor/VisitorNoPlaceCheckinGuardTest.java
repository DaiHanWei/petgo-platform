package com.tailtopia.profile.visitor;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import org.junit.jupiter.api.Test;

/**
 * V1.3.2 Story 1.6 · AC5.2 · L0 源码守卫：访客态（分享页 / 站内访客）<b>不下发</b>打卡条目（AD-9）。
 *
 * <p>访客投影只取 Diary 一个源，天然不含打卡；这条守卫防后来者为了「复用」把作者态的能力参数 / 打卡源接进访客层。
 */
class VisitorNoPlaceCheckinGuardTest {

    private static final Path DIR = Path.of("src/main/java/com/tailtopia/profile/visitor");

    @Test
    void visitorLayerNeverTouchesPlaceCheckinsOrCapabilities() throws IOException {
        for (String f : new String[] {"VisitorProjectionService.java", "VisitorPetController.java",
                "InAppVisitorPetController.java", "VisitorTimelineItem.java", "VisitorDayCell.java"}) {
            String code = Arrays.stream(Files.readString(DIR.resolve(f), StandardCharsets.UTF_8).split("\n"))
                    .filter(l -> !l.trim().startsWith("*") && !l.trim().startsWith("//") && !l.trim().startsWith("/*"))
                    .reduce("", (a, b) -> a + "\n" + b);
            assertThat(code).as(f).doesNotContain("PLACE_CHECKIN").doesNotContain("PlaceCheckinTimelineQuery")
                    .doesNotContain("supports").doesNotContain("TimelineCapabilities").doesNotContain("checkinPlace")
                    // V1.3.2 Story 3.3 · AC6.5：Tailsonality 条目同样不进访客态。
                    .doesNotContain("TAILSONALITY_BANNER").doesNotContain("TailsonalityTimelineQuery")
                    .doesNotContain("tailsonalityResultToken");
        }
    }
}
