import 'dart:async';

import 'package:flutter/material.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:tailtopia/core/im/im_service.dart';
import 'package:tailtopia/features/consult/presentation/im_chat_placeholder.dart';
import 'package:tailtopia/l10n/app_localizations.dart';

/// VetChat 聊天面（Story 5.5 live）：经 [ImService] 真实 C2C 收发的桥接。
/// 用 [_FakeImService] 替身验证：入站流上屏 / 发送乐观上屏 + 调 sendText。不触真实腾讯 SDK。
class _FakeImService implements ImService {
  final _incoming = StreamController<ImMessage>.broadcast();
  final List<String> sentTexts = [];
  final List<String> sentImages = [];
  bool loggedIn = false;

  @override
  Future<void> loginIfNeeded() async => loggedIn = true;

  @override
  Future<void> logout() async => loggedIn = false;

  @override
  Future<void> sendText({required String peerId, required String text}) async {
    sentTexts.add(text);
  }

  @override
  Future<void> sendImage({required String peerId, required String filePath}) async {
    sentImages.add(filePath);
  }

  @override
  Stream<ImMessage> onMessages(String peerId) => _incoming.stream;

  @override
  Stream<void> get inboundSignals => _incoming.stream.map((_) {});

  @override
  Future<Map<String, ImConversationSummary>> conversationSummaries(List<String> peerIds) async =>
      const {};

  @override
  Future<void> markRead(String peerId) async {}

  @override
  Future<List<ImMessage>> loadHistory(String peerId, {int count = 20}) async => const [];

  void emitPeer(String text) => _incoming.add(ImMessage(who: 'peer', text: text));
}

void main() {
  late _FakeImService fake;

  Widget host() => ProviderScope(
        overrides: [imServiceProvider.overrideWithValue(fake)],
        child: const MaterialApp(
          locale: Locale('id'),
          localizationsDelegates: AppLocalizations.localizationsDelegates,
          supportedLocales: AppLocalizations.supportedLocales,
          home: Scaffold(
            body: Column(children: [ImChatPlaceholder(peerId: 'v_1')]),
          ),
        ),
      );

  setUp(() => fake = _FakeImService());

  testWidgets('入站对端消息 → 渲染对端气泡', (tester) async {
    await tester.pumpWidget(host());
    await tester.pump(); // initState 订阅 + login future
    fake.emitPeer('Halo dok, kucing saya muntah');
    await tester.pump(); // 投递 broadcast 事件 → setState 标脏
    await tester.pump(); // 重建出气泡
    expect(find.text('Halo dok, kucing saya muntah'), findsOneWidget);
  });

  testWidgets('发送消息 → 乐观上屏己方气泡 + 调 sendText + 清空输入', (tester) async {
    await tester.pumpWidget(host());
    await tester.pump();

    await tester.enterText(find.byType(TextField), 'Terima kasih dok');
    await tester.pump();
    await tester.tap(find.byKey(const ValueKey('vetChatSend')));
    await tester.pump();

    expect(find.text('Terima kasih dok'), findsOneWidget); // 乐观己方气泡
    expect(fake.sentTexts, contains('Terima kasih dok')); // 真发到 service
    expect((tester.widget(find.byType(TextField)) as TextField).controller?.text, isEmpty);
  });

  testWidgets('peerId 为空 → 不订阅 / 不登录（只渲染壳）', (tester) async {
    await tester.pumpWidget(ProviderScope(
      overrides: [imServiceProvider.overrideWithValue(fake)],
      child: const MaterialApp(
        locale: Locale('id'),
        localizationsDelegates: AppLocalizations.localizationsDelegates,
        supportedLocales: AppLocalizations.supportedLocales,
        home: Scaffold(body: Column(children: [ImChatPlaceholder(peerId: null)])),
      ),
    ));
    await tester.pump();
    expect(fake.loggedIn, isFalse);
    expect(find.text('Perpesanan langsung terhubung di perangkat asli.'), findsOneWidget);
  });
}
