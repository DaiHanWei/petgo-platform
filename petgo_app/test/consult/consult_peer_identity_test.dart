import 'dart:io';

import 'package:flutter_test/flutter_test.dart';
import 'package:tailtopia/features/consult/domain/consult_session.dart';

/// 会话页顶栏的对端（兽医）身份（2026-08-07 bug）。
///
/// **现象**：staging 上会话归属 `vettest1`，但用户端顶栏显示的是另一个兽医的名字。
/// **根因**：顶栏的名字 / 头像首字母 / 在线点全是写死的占位，从没读过任何兽医数据 ——
/// 而写死的那个名字（`drh. Dewi Santoso`）恰好是真实存在的兽医账号，于是看起来像「会话串号」。
///
/// 因此本文件锁两件事：
/// 1. 源码里**不得再出现**任何具体人名 / 诊所名的字面量（这才是 bug 的本质）；
/// 2. 身份字段的解析与降级行为（拿不到名字 → 中性文案，不猜在线状态）。
void main() {
  group('🐛 回归：顶栏身份不得写死', () {
    final page = File('lib/features/consult/presentation/consult_conversation_page.dart')
        .readAsStringSync();

    // 改前写死的三个值。断言的是**字面量不在源码里**，而不是「渲染结果不等于它」——
    // 后者在换一个假名字后照样绿，挡不住同类问题再犯。
    const bannedLiterals = [
      'drh. Dewi Santoso', // 兽医名（真实账号，正是本次误判的来源）
      'Klinik Hewan Sehat', // 诊所名（后端根本没有这个字段）
    ];

    for (final banned in bannedLiterals) {
      test('源码不含写死的「$banned」', () {
        expect(page.contains("'$banned'"), isFalse,
            reason: '顶栏身份必须来自后端下发；写死的人名会让「串号」类问题极难排查');
        expect(page.contains('"$banned"'), isFalse);
      });
    }

    test('顶栏名字读的是后端字段，且有中性兜底', () {
      expect(page.contains('_vetName ?? l10n.consultVetFallbackName'), isTrue,
          reason: '取不到名字时必须回落中性文案，不得再填任何具体人名');
    });

    test('在线点只在后端明确说在线时才亮（不得恒亮）', () {
      expect(page.contains('_vetOnline == true'), isTrue,
          reason: '改前在线点恒亮，与兽医实际状态无关');
    });
  });

  group('身份字段解析与降级', () {
    Map<String, dynamic> base(Map<String, dynamic> extra) => {
          'id': 92,
          'status': 'IN_PROGRESS',
          'source': 'DIRECT',
          'vetId': 2,
          ...extra,
        };

    test('后端下发时原样取用', () {
      final s = ConsultSession.fromJson(base({
        'vetDisplayName': 'drh. Test Satu (vettest1)',
        'vetAvatarUrl': 'https://cdn/x.jpg',
        'vetOnline': true,
      }));
      expect(s.vetDisplayName, 'drh. Test Satu (vettest1)');
      expect(s.vetAvatarUrl, 'https://cdn/x.jpg');
      expect(s.vetOnline, isTrue);
    });

    test('缺字段 / 空串 → null（顶栏据此走中性兜底，不渲染空白）', () {
      final missing = ConsultSession.fromJson(base({}));
      expect(missing.vetDisplayName, isNull);
      expect(missing.vetAvatarUrl, isNull);
      expect(missing.vetOnline, isNull, reason: '在线态未知就是未知，不得默认成「在线」');

      final blank = ConsultSession.fromJson(
          base({'vetDisplayName': '   ', 'vetAvatarUrl': ''}));
      expect(blank.vetDisplayName, isNull);
      expect(blank.vetAvatarUrl, isNull);
    });
  });

  group('头像首字母', () {
    test('跳过 drh./dr. 头衔取真名首字母 —— 否则满屏都是 D', () {
      // 这正是改前那个写死的 `D` 的来源：印尼语兽医名普遍带 `drh.` 前缀。
      expect(ConsultSession.initialOf('drh. Dewi Santoso'), 'D');
      expect(ConsultSession.initialOf('drh. Test Satu (vettest1)'), 'T');
      expect(ConsultSession.initialOf('dr. Budi'), 'B');
    });

    test('无名字 → ?（不再写死 D）', () {
      expect(ConsultSession.initialOf(null), '?');
      expect(ConsultSession.initialOf('   '), '?');
    });

    test('只有头衔时退回头衔本身，不返回空', () {
      expect(ConsultSession.initialOf('drh.'), 'D');
    });

    test('非 BMP 字符不被劈成半个码元', () {
      expect(ConsultSession.initialOf('😺 Mochi'), '😺');
    });
  });
}
