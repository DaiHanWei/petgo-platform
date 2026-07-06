import 'dart:async';

import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:tailtopia/core/im/im_service.dart';
import 'package:tailtopia/features/auth/domain/auth_state.dart';

/// 跨用户 IM 会话隔离（隐私回归）：同设备 A 登出 / 注销后，B 不得看到 A 的兽医聊天。
///
/// 根因：IM 会话生命周期未绑定 App 登录态——用户侧登出只清 JWT，从不登出 IM，
/// 致腾讯 IM SDK 仍以 A 身份登录，B 进会话 loginIfNeeded 幂等空转 → 拉到 A↔兽医 历史。
/// 断言：`toGuest()`（登出 / 注销 / 强制 401 / 引导中止的唯一收口）必须登出 IM。
class _FakeImService implements ImService {
  int logoutCalls = 0;

  @override
  Future<void> logout() async => logoutCalls++;

  @override
  Future<void> loginIfNeeded() async {}

  @override
  Future<void> sendText({required String peerId, required String text}) async {}

  @override
  Future<void> sendImage({required String peerId, required String filePath}) async {}

  @override
  Stream<ImMessage> onMessages(String peerId) => const Stream.empty();

  @override
  Stream<void> get inboundSignals => const Stream.empty();

  @override
  Future<Map<String, ImConversationSummary>> conversationSummaries(List<String> peerIds) async =>
      const {};

  @override
  Future<void> markRead(String peerId) async {}

  @override
  Future<List<ImMessage>> loadHistory(String peerId, {int count = 20}) async => const [];
}

void main() {
  TestWidgetsFlutterBinding.ensureInitialized();

  test('toGuest 登出 IM（同设备 A→B 不泄漏兽医聊天）', () async {
    final fake = _FakeImService();
    final container = ProviderContainer(
      overrides: [imServiceProvider.overrideWithValue(fake)],
    );
    addTearDown(container.dispose);

    container.read(authControllerProvider.notifier).toGuest();
    // logout 是 best-effort fire-and-forget，等微任务队列排空。
    await Future<void>.delayed(Duration.zero);

    expect(fake.logoutCalls, greaterThanOrEqualTo(1),
        reason: '会话终止必须解绑 IM 登录，否则下一用户会看到上一用户的兽医聊天');
  });
}
