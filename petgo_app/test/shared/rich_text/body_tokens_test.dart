import 'package:flutter_test/flutter_test.dart';
import 'package:tailtopia/shared/rich_text/body_tokens.dart';

/// L0：`body_tokens.dart` 中样例之外的 API（V1.4.2 social-topic · Story 4.4 · AC1）。
void main() {
  group('topicQueryAt', () {
    test('刚打 # → 空关键词', () {
      final q = topicQueryAt('halo #', 6)!;
      expect((q.start, q.end, q.keyword), (5, 6, ''));
    });
    test('#ku| → ku', () {
      final q = topicQueryAt('#ku', 3)!;
      expect((q.start, q.end, q.keyword), (0, 3, 'ku'));
    });
    test('abc#| → null（# 不在词首）', () => expect(topicQueryAt('abc#', 4), isNull));
    test('#kucing | → null（空格结束）', () => expect(topicQueryAt('#kucing ', 8), isNull));
    test('31 字 → null；30 字可以', () {
      final s31 = '#${'a' * 31}';
      final s30 = '#${'a' * 30}';
      expect(topicQueryAt(s31, s31.length), isNull);
      expect(topicQueryAt(s30, s30.length)!.keyword, 'a' * 30);
    });
    test('光标在 0 / 越界 → null', () {
      expect(topicQueryAt('#ku', 0), isNull);
      expect(topicQueryAt('#ku', 4), isNull);
      expect(topicQueryAt('', 0), isNull);
    });
    test('代理对字母按码点回扫', () {
      final q = topicQueryAt('hai #𠀀𠀁', 9)!;
      expect((q.start, q.keyword), (4, '𠀀𠀁'));
    });
    test('(#ku 可以；全角 ＃ 不算', () {
      expect(topicQueryAt('(#ku', 4)!.keyword, 'ku');
      expect(topicQueryAt('＃ku', 3), isNull);
    });
  });

  group('isValidTopicName / normalizeTopic', () {
    test('长度、数字、字符', () {
      expect(isValidTopicName('a'), isFalse);
      expect(isValidTopicName('ab'), isTrue);
      expect(isValidTopicName('2024'), isFalse);
      expect(isValidTopicName('2024_'), isTrue);
      expect(isValidTopicName('ku cing'), isFalse);
      expect(isValidTopicName('a' * 31), isFalse);
      expect(isValidTopicName(''), isFalse);
    });
    test('标准名小写', () => expect(normalizeTopic('KucingOren'), 'kucingoren'));
    // 与 Java toLowerCase(Locale.ROOT) 实测输出对齐（复审 2026-11-05）。
    test('Final_Sigma：词尾 Σ → ς；词中 / 非 cased 之后 → σ', () {
      expect(normalizeTopic('ΟΔΟΣ'), 'οδος');
      expect(normalizeTopic('ΑΣ_'), 'ας_');
      expect(normalizeTopic('ΑΣ1'), 'ας1');
      expect(normalizeTopic('ΑΣ\u0301'), 'ας\u0301'); // 之后的组合附标跳过
      expect(normalizeTopic('ΑΣΑ'), 'ασα');
      expect(normalizeTopic('ΣΑ'), 'σα');
      expect(normalizeTopic('猫Σ'), '猫σ');
      expect(normalizeTopic('Σ'), 'σ');
    });
    test('İ → i + U+0307（Dart 原生会丢点）', () {
      expect(normalizeTopic('İzmir'), 'i\u0307zmir');
      expect(normalizeTopic('aİ'), 'ai\u0307');
      expect(isValidTopicName('İ${'a' * 29}'), isFalse); // 标准名 31 码点
    });
    test('幂等', () {
      for (final s in ['ΟΔΟΣ', 'İzmir', 'KucingOren', 'ΑΣΑ']) {
        expect(normalizeTopic(normalizeTopic(s)), normalizeTopic(s));
      }
    });
  });

  group('mentionRanges（自 MentionText._locate 搬迁，行为一致）', () {
    test('An / Ana：长昵称优先、边界不误切', () {
      final r = mentionRanges('halo @Ana dan @An', [(userId: 1, nickname: 'An'), (userId: 2, nickname: 'Ana')],
          dropAmbiguous: true);
      expect(r, [
        const MarkRange(start: 5, end: 9, userId: 2),
        const MarkRange(start: 14, end: 17, userId: 1),
      ]);
    });
    test('同名两人：dropAmbiguous 开 → 不高亮；关 → 高亮', () {
      final refs = [(userId: 1, nickname: 'Budi'), (userId: 2, nickname: 'Budi')];
      expect(mentionRanges('halo @Budi', refs, dropAmbiguous: true), isEmpty);
      expect(mentionRanges('halo @Budi', refs, dropAmbiguous: false),
          [const MarkRange(start: 5, end: 10, userId: 1)]);
    });
    test('词中 @ 不算（mail@Aurel）', () {
      expect(mentionRanges('mail@Aurel', [(userId: 1, nickname: 'Aurel')], dropAmbiguous: true), isEmpty);
    });
  });

  group('mergeMarks：@ 与 # 重叠时 @ 优先', () {
    test('昵称里含 #', () {
      const text = '@Rara#kucing dan #lucu';
      final mentions = mentionRanges(text, [(userId: 9, nickname: 'Rara#kucing')], dropAmbiguous: true);
      final merged = mergeMarks(mentions, topicsIn(text));
      expect(merged.map((m) => (m.start, m.end, m.userId, m.topicNorm)).toList(), [
        (0, 12, 9, null),
        (17, 22, null, 'lucu'),
      ]);
    });
    test('话题落在 @ 区间内被丢弃', () {
      const text = '@Ra #kucing ok';
      final mentions = mentionRanges(text, [(userId: 9, nickname: 'Ra #kucing')], dropAmbiguous: true);
      expect(mentions.single.end, 11);
      final merged = mergeMarks(mentions, topicsIn(text));
      expect(merged.length, 1);
      expect(merged.single.userId, 9);
    });
  });

  group('matchServerTopicName（NFD 容错兜底）', () {
    test('精确命中优先', () => expect(matchServerTopicName('kucing', {'kucing', 'lucu'}), 'kucing'));
    test('App NFD norm ↔ 服务端 NFC 名', () {
      expect(matchServerTopicName('pe\u0301', {'p\u00e9'}), 'p\u00e9');
      expect(matchServerTopicName('cafe\u0301', {'caf\u00e9', 'lucu'}), 'caf\u00e9');
    });
    test('不像就不命中；基字符不同不命中', () {
      expect(matchServerTopicName('pa\u0301', {'pe'}), isNull);
      expect(matchServerTopicName('pe', {'p\u00e9'}), isNull); // App 没附标 → 不放宽
      expect(matchServerTopicName('pe\u0301x', {'p\u00e9'}), isNull);
    });
    test('多个服务端名都像 → 不命中（宁缺毋错）', () {
      expect(matchServerTopicName('pe\u0301', {'p\u00e9', 'p\u00e8'}), isNull);
    });
    test('天城文附标（服务端 NFC 仍是 基+附标）→ 精确命中', () {
      expect(matchServerTopicName('बिल्ली', {'बिल्ली'}), 'बिल्ली');
    });
  });
}
