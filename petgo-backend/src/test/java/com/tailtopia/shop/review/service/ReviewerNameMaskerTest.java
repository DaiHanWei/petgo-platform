package com.tailtopia.shop.review.service;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** L0（v1.4.2 shop Story 6.1 复审修复）：评价公开昵称打码「首词 + 末词首字母.」。 */
class ReviewerNameMaskerTest {

    private static String m(String s) {
        return ReviewerNameMasker.mask(s);
    }

    @Test
    @DisplayName("两词 / 多词：首词 + 最后一词首字母 + 点；中间词丢弃")
    void multiWord() {
        assertThat(m("Dewi Sartika")).isEqualTo("Dewi S.");
        assertThat(m("Dewi Ayu Sartika")).isEqualTo("Dewi S.");
        assertThat(m("budi santoso")).isEqualTo("budi s.");
    }

    @Test
    @DisplayName("单词名只保留该词")
    void singleWord() {
        assertThat(m("Sukarno")).isEqualTo("Sukarno");
        assertThat(m("  Sukarno  ")).isEqualTo("Sukarno");
        assertThat(m("王小明")).isEqualTo("王小明");
    }

    @Test
    @DisplayName("null / 空串 / 全空白（含 NBSP、全角空格、零宽空格）→ null")
    void blankIsNull() {
        assertThat(m(null)).isNull();
        assertThat(m("")).isNull();
        assertThat(m("   \t\n")).isNull();
        assertThat(m("\u00A0\u3000\u200B")).isNull();
    }

    @Test
    @DisplayName("多空格 / Tab / NBSP / 全角空格都当一个分隔")
    void unicodeWhitespace() {
        assertThat(m("Dewi    Sartika")).isEqualTo("Dewi S.");
        assertThat(m("Dewi\tSartika")).isEqualTo("Dewi S.");
        assertThat(m("Dewi\u00A0Sartika")).isEqualTo("Dewi S.");
        assertThat(m("Dewi\u3000Sartika")).isEqualTo("Dewi S.");
        assertThat(m("\u00A0Dewi Sartika\u00A0")).isEqualTo("Dewi S.");
    }

    @Test
    @DisplayName("Unicode：末词首字符按字素簇取（重音组合字符、emoji、ZWJ 序列不被劈开）")
    void graphemes() {
        assertThat(m("Ana Émile")).isEqualTo("Ana É.");
        assertThat(m("Ana E\u0301mile")).isEqualTo("Ana E\u0301.");
        assertThat(m("Mochi 🐶lover")).isEqualTo("Mochi 🐶.");
        assertThat(m("Mochi 👨\u200D👩\u200D👧fam")).isEqualTo("Mochi 👨\u200D👩\u200D👧.");
        assertThat(m("🐱 Kitty")).isEqualTo("🐱 K.");
        assertThat(m("李 小明")).isEqualTo("李 小.");
    }
}
