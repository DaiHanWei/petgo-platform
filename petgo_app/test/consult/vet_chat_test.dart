import 'package:flutter/material.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:petgo/features/consult/presentation/im_chat_placeholder.dart';

/// VetChat 聊天面（PetGo Prototype 换肤）：种子气泡 + 发送→打字→兽医回复。
void main() {
  Widget host() => const MaterialApp(
        home: Scaffold(
          body: Column(children: [ImChatPlaceholder(imConversationId: 'demo')]),
        ),
      );

  setUp(() {
    // 高视口：让聊天 ListView 全量构建（新发送的气泡在底部，否则懒加载不构建）。
    final b = TestWidgetsFlutterBinding.ensureInitialized();
    b.platformDispatcher.views.first.physicalSize = const Size(1170, 6000);
    b.platformDispatcher.views.first.devicePixelRatio = 3.0;
  });

  tearDown(() {
    final b = TestWidgetsFlutterBinding.ensureInitialized();
    b.platformDispatcher.views.first.resetPhysicalSize();
    b.platformDispatcher.views.first.resetDevicePixelRatio();
  });

  testWidgets('种子对话气泡渲染', (tester) async {
    await tester.pumpWidget(host());
    await tester.pump();
    expect(find.textContaining('Konsultasi dengan drh. Sari dimulai'), findsOneWidget);
    expect(find.textContaining('muntah busa putih'), findsOneWidget);
    expect(find.textContaining('puasakan makanan'), findsOneWidget);
    expect(find.text('foto anabul'), findsOneWidget); // 照片占位气泡
  });

  testWidgets('发送消息 → 出现己方气泡 → 1.5s 后兽医罐头回复', (tester) async {
    await tester.pumpWidget(host());
    await tester.pump();

    await tester.enterText(find.byType(TextField), 'Terima kasih dok');
    await tester.pump();
    await tester.tap(find.byKey(const ValueKey('vetChatSend')));
    await tester.pump();
    expect(find.text('Terima kasih dok'), findsOneWidget); // 己方气泡即时出现（输入框已清空）

    // 打字延迟内未回复；越过 1.5s 后兽医回复出现。
    await tester.pump(const Duration(milliseconds: 1600));
    expect(find.textContaining('Tetap pantau kondisinya'), findsOneWidget);
  });
}
