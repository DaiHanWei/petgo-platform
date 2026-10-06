import 'dart:ui' show Locale;

import 'package:flutter_test/flutter_test.dart';
import 'package:tailtopia/features/tailsonality/domain/content/ts_dialog_copy.dart';
import 'package:tailtopia/features/tailsonality/domain/content/ts_match_copy.dart';
import 'package:tailtopia/features/tailsonality/domain/content/ts_questions.dart';
import 'package:tailtopia/features/tailsonality/domain/content/ts_readings.dart';
import 'package:tailtopia/features/tailsonality/domain/content/ts_roles.dart';
import 'package:tailtopia/features/tailsonality/domain/content/ts_text.dart';

/// V1.3.2 Story 2.2 · AC1 / AC2.4：内容表机检（可机检的翻译质量 + 结构 + 逐字条目）。
///
/// 语感复核不在这里（发版检查单 RC-5，印尼语母语同事过一遍）。
void main() {
  // 汉字（含扩展区）/ 假名 / 谚文 / 全角与中日韩标点（抓「复制了原文忘了翻」）。
  final cjk = RegExp(
    r'[\p{Script=Han}\p{Script=Hiragana}\p{Script=Katakana}\p{Script=Hangul}\u3000-\u303f\uff00-\uffef]',
    unicode: true,
  );
  final brace = RegExp(r'\{([^}]*)\}');

  /// 全部可翻译字段，`label → TsText`。
  Map<String, TsText> allTexts() {
    final m = <String, TsText>{};
    kTsQuestions.forEach((k, q) {
      m['$k.stem'] = q.stem;
      for (var i = 0; i < q.options.length; i++) {
        m['$k.opt$i'] = q.options[i];
      }
    });
    kTsRoles.forEach((k, r) {
      m['role.$k.summary'] = r.summary;
      m['role.$k.deepRead'] = r.deepRead;
    });
    kTsDimensionReadings.forEach((k, v) => m['dim.$k'] = v);
    kTsEnergy.forEach((k, v) {
      m['energy.$k.label'] = v.label;
      m['energy.$k.line'] = v.line;
    });
    kTsMatchTiers.forEach((k, v) {
      m['tier.$k.name'] = v.name;
      m['tier.$k.review'] = v.review;
      m['tier.$k.summary'] = v.summary;
    });
    kTsAxisDiffLines.forEach((k, v) => m['diff.$k'] = v);
    kTsAxisDetails.forEach((k, v) => m['detail.$k'] = v);
    for (final (name, d) in [('retention', kTsRetentionDialog), ('retake', kTsRetakeDialog)]) {
      m['$name.title'] = d.title;
      m['$name.body'] = d.body;
      m['$name.confirm'] = d.confirm;
      m['$name.cancel'] = d.cancel;
    }
    return m;
  }

  /// 两语同词是**有意为之**的条目（不是漏翻）：配型档位名是专名不译（D-18）；挽留弹窗的「Unlock」内容设计原文两语同词。
  bool sameInBothAllowed(String label) => label.startsWith('tier.') && label.endsWith('.name') || label == 'retention.confirm';

  group('AC2.4 翻译机检', () {
    test('每条 EN / ID 非空、不含中日韩字符', () {
      allTexts().forEach((label, t) {
        expect(t.en.trim(), isNotEmpty, reason: '$label.en');
        expect(t.id.trim(), isNotEmpty, reason: '$label.id');
        expect(cjk.hasMatch(t.en), isFalse, reason: '$label.en 含中文：${t.en}');
        expect(cjk.hasMatch(t.id), isFalse, reason: '$label.id 含中文：${t.id}');
      });
    });

    test('可翻译字段 en != id（抓两栏贴了同一句）', () {
      allTexts().forEach((label, t) {
        if (sameInBothAllowed(label)) return;
        expect(t.en, isNot(t.id), reason: label);
      });
    });

    test('中日韩检测本身有效（含扩展区汉字 / 假名 / 谚文）', () {
      for (final s in ['猫', '𠀋', 'ねこ', 'ネコ', '고양이', '，']) {
        expect(cjk.hasMatch(s), isTrue, reason: s);
      }
      expect(cjk.hasMatch("{pet}'s — café …"), isFalse);
    });

    test('花括号占位只出现 {pet}', () {
      final all = [
        ...allTexts().values.expand((t) => [t.en, t.id]),
        ...kTsRoles.values.expand((r) => [r.name, r.slogan]),
        ...kTsMatchTiers.values.map((t) => t.slogan ?? ''),
      ];
      for (final s in all) {
        for (final m in brace.allMatches(s)) {
          expect(m.group(1), 'pet', reason: s);
        }
      }
    });

    test('角色名 / slogan / 档位 slogan 非空且不含中文', () {
      for (final r in kTsRoles.values) {
        for (final s in [r.name, r.slogan]) {
          expect(s.trim(), isNotEmpty);
          expect(cjk.hasMatch(s), isFalse, reason: s);
          expect(s.contains('*'), isFalse, reason: 'slogan 不带内容设计里的斜体星号：$s');
        }
      }
      for (final t in kTsMatchTiers.values) {
        expect(t.slogan, isNotNull);
        expect(cjk.hasMatch(t.slogan!), isFalse);
      }
    });
  });

  group('结构', () {
    const sets = ['CAT', 'DOG', 'GENERAL'];
    final qids = [for (var i = 1; i <= 15; i++) 'Q$i', 'P1', 'P2', 'P3'];

    test('题目 54 键、每题 4 选项；P1..P3 带 imageGroup，Q* 不带', () {
      expect(kTsQuestions.keys.toSet(), {for (final s in sets) for (final q in qids) '$s.$q'});
      kTsQuestions.forEach((k, q) {
        expect(q.options, hasLength(4), reason: k);
        final isImage = k.endsWith('.P1') || k.endsWith('.P2') || k.endsWith('.P3');
        if (isImage) {
          expect(q.imageGroup, 'p${k.substring(k.length - 1)}', reason: k);
        } else {
          expect(q.imageGroup, isNull, reason: k);
        }
      });
    });

    test('图片题三套共用同一题（§6.5）', () {
      for (final p in ['P1', 'P2', 'P3']) {
        expect(identical(kTsQuestions['CAT.$p'], kTsQuestions['DOG.$p']), isTrue);
        expect(identical(kTsQuestions['CAT.$p'], kTsQuestions['GENERAL.$p']), isTrue);
      }
    });

    test('角色 16 键 = 16 个四字母代号；维度 / 能量 / 档位 / 差异句 / 逐轴详解键集', () {
      final codes = <String>{
        for (final a in 'EI'.split(''))
          for (final b in 'NS'.split(''))
            for (final c in 'TF'.split(''))
              for (final d in 'JP'.split('')) '$a$b$c$d',
      };
      expect(kTsRoles.keys.toSet(), codes);
      expect(kTsDimensionReadings.keys.toSet(), {'E', 'I', 'N', 'S', 'T', 'F', 'J', 'P'});
      expect(kTsEnergy.keys.toSet(), {'H', 'L'});
      expect(kTsMatchTiers.keys.toSet(), {0, 1, 2, 3, 4});
      expect(kTsAxisDiffLines.keys.toSet(), {'EI', 'IE', 'NS', 'SN', 'TF', 'FT', 'JP', 'PJ'});
      expect(kTsAxisDetails.keys.toSet(), {
        for (final pair in ['EI', 'NS', 'TF', 'JP'])
          for (final o in pair.split(''))
            for (final p in pair.split('')) '$o$p',
      });
    });
  });

  group('AC1.3 / AC1.4 逐字条目', () {
    test('能量标签', () {
      expect(kTsEnergy['H']!.label.en, 'High energy');
      expect(kTsEnergy['H']!.label.id, 'Energi tinggi');
      expect(kTsEnergy['L']!.label.en, 'Low energy');
      expect(kTsEnergy['L']!.label.id, 'Energi rendah');
    });

    test('档位名（D-18）/ 总结句 / 差异句 / 弹窗', () {
      expect(kTsMatchTiers[4]!.name.id, 'Literally Twins');
      expect([for (var k = 4; k >= 0; k--) kTsMatchTiers[k]!.name.en],
          ['Literally Twins', 'Twin Flames', 'Backs Together', 'Counterweight', 'Magnetic Poles']);
      expect(kTsMatchTiers[0]!.summary.id, 'Nol huruf yang sama. Wow.');
      expect(kTsMatchTiers[2]!.review.id,
          'Setengah kamu, setengah dia sendiri. Cukup buat saling ngerti, cukup juga buat saling julid.');
      expect(kTsAxisDiffLines['FT']!.en, "You're in your feelings, it's asleep.");
      expect(kTsAxisDiffLines['FT']!.id, 'Kamu lagi baper, dia lagi tidur.');
      expect(kTsRetentionDialog.title.id, 'Yakin keluar?');
      expect(kTsRetentionDialog.cancel.en, 'Not now');
      expect(kTsRetakeDialog.body.id, contains('**hasil barunya perlu di-unlock lagi**'));
      expect(kTsRetakeDialog.title.en, 'Retake the test?');
    });

    test('角色名 / slogan 照搬（代表值）', () {
      expect(kTsRoles['ENTJ']!.name, 'Literally CEO Banget');
      expect(kTsRoles['ENTJ']!.slogan, 'CEO of this house, literally.');
      expect(kTsRoles['ISFP']!.name, 'Sus Radar 24/7');
      expect(kTsRoles['ESFJ']!.slogan, 'Kamu ke mana, aku ke situ.');
    });
  });

  group('TsText 工具', () {
    test('of(locale)：id 取印尼语，其余取英语', () {
      const t = (en: 'Hi', id: 'Halo');
      expect(t.of(const Locale('id')), 'Halo');
      expect(t.of(const Locale('en')), 'Hi');
      expect(t.of(const Locale('zh')), 'Hi');
    });

    test('tsFillPet 替换全部 {pet}', () {
      expect(tsFillPet('{pet} dan {pet}', 'Momo'), 'Momo dan Momo');
    });
  });
}
