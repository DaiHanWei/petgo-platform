import 'dart:io';

import 'package:flutter_test/flutter_test.dart';

/// V1.3.0 batch-b1 Story 1.7 · L0：评论输入条的**形态约束**（AC3 / AC4 · B1-D3）。
///
/// <h2>为什么用扫源码的方式守 AC4</h2>
/// AC4 是一条**语义**约束：「👍/👎 不得与发送键并列」。并列了也照样能用、也不会有别的测试变红，
/// 只是那两个按钮会被读成「给这个场所点赞/点踩」的独立动作 ——
/// 而 PRD 定的是**评论自带的态度**（不写评论不能表态）。2026-09-11 的 B1-D3 就是为这件事
/// 专门改的 UI 稿。真正的视觉验收是 L2（要真机看），云端能做的是钉住结构：
/// 态度行**包在 `_expanded` 条件里**、且在输入行**之前**。
///
/// ⚠️ 这不是替代 L2，是在它之前先挡住"顺手把两行合并了"。
void main() {
  final src = File('lib/features/place/presentation/place_comment_composer.dart')
      .readAsStringSync();
  final codeLines = src
      .split('\n')
      .where((l) => !l.trimLeft().startsWith('//') && !l.trimLeft().startsWith('///'))
      .join('\n');

  group('🔴 AC4：态度行只在展开态出现，且不与发送键并列', () {
    test('态度 chip 包在 _expanded 条件里', () {
      final guardAt = codeLines.indexOf('if (_expanded)');
      final chipAt = codeLines.indexOf('placeAttitudeRecommend');
      expect(guardAt, greaterThanOrEqualTo(0),
          reason: '🔴 没有展开态判断 = 态度行一进页面就在，那正是 B1-D3 改掉的摆法');
      expect(chipAt, greaterThan(guardAt),
          reason: '🔴 态度 chip 跑到了展开态判断之前');
    });

    test('态度行在输入行与发送键之前（同一排 = 被读成给场所点赞）', () {
      final chipAt = codeLines.indexOf('placeAttitudeRecommend');
      final inputAt = codeLines.indexOf('placeCommentInput');
      final sendAt = codeLines.indexOf('placeCommentSend');
      expect(chipAt, lessThan(inputAt),
          reason: '🔴 态度行排到输入框之后 = 与发送键同排');
      expect(chipAt, lessThan(sendAt));
    });

    test('展开的触发是输入框获焦，不是别的什么', () {
      expect(codeLines.contains('_focusNode.hasFocus'), isTrue,
          reason: 'AC4 的原话是「输入框获焦后出现」');
    });
  });

  group('🔴 B1-D3：不写评论就不能表态', () {
    test('发送键的可用条件只看正文，不看态度', () {
      expect(codeLines.contains("_controller.text.trim().isNotEmpty"), isTrue);
      // 可用条件里出现 _attitude = 有人把"选了态度"也算成可以发送。
      final canSend = RegExp(r'bool get _canSend =>[^;]*;').firstMatch(codeLines);
      expect(canSend, isNotNull, reason: '_canSend 改名了就改这条测试');
      expect(canSend!.group(0)!.contains('_attitude'), isFalse,
          reason: '🔴 只选态度不写正文就能发 = 「不写评论不能表态」被改掉了');
    });

    test('空正文时 _send 直接返回，不发请求', () {
      expect(codeLines.contains('if (text.isEmpty'), isTrue);
    });
  });

  group('🔴 AC3：态度是可选的', () {
    test('再点一次已选中的 chip 可以取消表态', () {
      expect(codeLines.contains('_attitude == v ? null : v'), isTrue,
          reason: '🔴 没有这条的话，手滑点了 👎 就只能在两者间二选一，回不到"不表态"');
    });

    test('发送成功后态度复位（下一条评论不该继承上一条的立场）', () {
      expect(codeLines.contains('_attitude = null'), isTrue);
    });
  });

  /// 🔒 评论**列表**对游客开放，登录墙只在"要发言"这一刻出现。
  test('游客态走 requireLogin，而不是把整个评论区藏起来', () {
    expect(codeLines.contains('requireLogin'), isTrue);
    expect(codeLines.contains('AuthStatus.guest'), isTrue);
  });
}
