import 'dart:convert';

import 'package:flutter_test/flutter_test.dart';
import 'package:tailtopia/core/im/im_service.dart';
import 'package:tailtopia/core/push/push_service.dart';
import 'package:tailtopia/core/router/deep_link_routes.dart';

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

  group('shouldRegister（注册门控判定）', () {
    test('兽医恒可注册（不受 F7 约束——新单推送是工作刚需）', () {
      expect(shouldRegister(isVet: true, f7Asked: false, permissionGranted: false), isTrue);
    });

    test('C 端过了 F7 门 → 可注册', () {
      expect(shouldRegister(isVet: false, f7Asked: true, permissionGranted: false), isTrue);
    });

    test('存量用户缺口：未过 F7 但系统权限已授予 → 仍可注册', () {
      // L2 2026-08-07 实测：老账号换机/重装后 F7 标记清空且不再走建档，
      // 缺此旁路即永久收不到推送。已授权时注册不弹窗，不违反 FR-22D。
      expect(shouldRegister(isVet: false, f7Asked: false, permissionGranted: true), isTrue);
    });

    test('C 端未过 F7 且未授权 → 不注册（F7 独占弹窗时机）', () {
      expect(shouldRegister(isVet: false, f7Asked: false, permissionGranted: false), isFalse);
    });
  });

  group('pushClickLocation（点击落点分流）', () {
    test('通知类（有 token）→ 通知中心，忽略深链目标', () {
      expect(
        pushClickLocation(hasToken: true, deepLink: '/content/357'),
        DeepLinkRoutes.notificationsCenter,
      );
    });

    test('IM 会话消息（无 token）→ 落会话，不去通知中心', () {
      // 中心无此条目，送去中心=死路（用户看不到该消息）。
      expect(
        pushClickLocation(hasToken: false, deepLink: '/consult/conversation/88'),
        '/consult/conversation/88',
      );
    });

    test('IM 类但深链已兜底成通知中心 → 按原样返回', () {
      expect(
        pushClickLocation(hasToken: false, deepLink: DeepLinkRoutes.notificationsCenter),
        DeepLinkRoutes.notificationsCenter,
      );
    });
  });
}
