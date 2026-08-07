import 'dart:convert';

import 'package:flutter_test/flutter_test.dart';
import 'package:tailtopia/core/im/im_service.dart';
import 'package:tailtopia/core/push/push_service.dart';

/// 推送接入（spec-push-timpush-integration）L0：ext 透传契约的两端——
/// 解析（[parsePushExt]，点击回调入口）与生成（[ChatPushSpec]，会话消息发送端）。
void main() {
  group('parsePushExt', () {
    test('完整载荷：type + token + targetRef', () {
      final p = parsePushExt('{"type":"CONTENT_LIKED","token":"abc123","targetRef":"55"}');
      expect(p.type, 'CONTENT_LIKED');
      expect(p.token, 'abc123');
      expect(p.targetRef, '55');
    });

    test('会话消息载荷：无 token（发送端附带，非通知中心行）', () {
      final p = parsePushExt('{"type":"VET_REPLY","targetRef":"88"}');
      expect(p.type, 'VET_REPLY');
      expect(p.token, isNull);
      expect(p.targetRef, '88');
    });

    test('targetRef 数字型 JSON 值也归一为字符串', () {
      final p = parsePushExt('{"type":"VET_REPLY","targetRef":88}');
      expect(p.targetRef, '88');
    });

    test('非 JSON / 非对象 / 空串 → 空载荷（点击兜底通知中心，不崩）', () {
      expect(parsePushExt('not-json').type, isNull);
      expect(parsePushExt('[1,2]').type, isNull);
      expect(parsePushExt('').type, isNull);
    });
  });

  group('ChatPushSpec', () {
    test('生成中性文案 + VET_REPLY ext（不含消息内容）', () {
      final info = const ChatPushSpec(sessionId: '42').toOfflinePushInfo();
      expect(info.title, ChatPushSpec.neutralTitle);
      expect(info.desc, ChatPushSpec.neutralDesc);
      final ext = jsonDecode(info.ext!) as Map<String, dynamic>;
      expect(ext, {'type': 'VET_REPLY', 'targetRef': '42'});
    });

    test('与 parsePushExt 往返一致（发送端生成的 ext 点击端可解析）', () {
      final info = const ChatPushSpec(sessionId: '7').toOfflinePushInfo();
      final p = parsePushExt(info.ext!);
      expect(p.type, 'VET_REPLY');
      expect(p.targetRef, '7');
      expect(p.token, isNull);
    });
  });
}
