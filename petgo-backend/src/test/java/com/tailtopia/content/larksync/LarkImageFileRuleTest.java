package com.tailtopia.content.larksync;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/** 2026-09-07：云盘文件名规则放宽（png / 大小写 / 无序号单图）+ content-type 按魔数。 */
class LarkImageFileRuleTest {

    private static Map<String, String> folder(String... names) {
        Map<String, String> m = new LinkedHashMap<>();
        for (String n : names) {
            m.put(n, "tok_" + n);
        }
        return m;
    }

    @Test
    void pngAndUppercaseExtensionsAreAccepted() {
        Map<String, String> f = folder("W4K101-3.png", "W4K101-1.PNG", "W4K101-2.Jpeg", "other-1.jpg");
        assertThat(LarkContentSyncService.resolveImageFiles(List.of("W4K101"), f))
                .containsExactly("W4K101-1.PNG", "W4K101-2.Jpeg", "W4K101-3.png");
    }

    @Test
    void singleImageWithoutSequenceSuffixIsAccepted() {
        Map<String, String> f = folder("DR260828001.jpg", "DR260828002.jpg");
        assertThat(LarkContentSyncService.resolveImageFiles(List.of("DR260828001"), f))
                .containsExactly("DR260828001.jpg");
    }

    @Test
    void unsuffixedFileSortsBeforeNumberedOnes() {
        Map<String, String> f = folder("A-2.jpg", "A.jpg", "A-1.jpg");
        assertThat(LarkContentSyncService.resolveImageFiles(List.of("A"), f))
                .containsExactly("A.jpg", "A-1.jpg", "A-2.jpg");
    }

    @Test
    void spacesAroundHyphenAreTolerated() {
        Map<String, String> f = folder("W2K104 - 1.png", "W2K105 -1.png", "W2K106- 2.png", "W2K106 -1.png");
        assertThat(LarkContentSyncService.resolveImageFiles(List.of("W2K104"), f)).containsExactly("W2K104 - 1.png");
        assertThat(LarkContentSyncService.resolveImageFiles(List.of("W2K106"), f))
                .containsExactly("W2K106 -1.png", "W2K106- 2.png");
    }

    @Test
    void prefixMustMatchExactly() {
        Map<String, String> f = folder("DR260828001.jpg", "DR2608280011-1.jpg");
        assertThat(LarkContentSyncService.resolveImageFiles(List.of("DR260828001"), f))
                .containsExactly("DR260828001.jpg");
        assertThatThrownBy(() -> LarkContentSyncService.resolveImageFiles(List.of("DR2608280"), f))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("缺图");
    }

    @Test
    void contentTypeBySniffing() {
        assertThat(LarkContentSyncService.contentTypeOf(new byte[] {(byte) 0x89, 'P', 'N', 'G', 0, 0, 0, 0}))
                .isEqualTo("image/png");
        assertThat(LarkContentSyncService.contentTypeOf(new byte[] {(byte) 0xFF, (byte) 0xD8, (byte) 0xFF, 0}))
                .isEqualTo("image/jpeg");
        assertThat(LarkContentSyncService.contentTypeOf(new byte[] {'G', 'I', 'F', '8'})).isEqualTo("image/gif");
    }
}
