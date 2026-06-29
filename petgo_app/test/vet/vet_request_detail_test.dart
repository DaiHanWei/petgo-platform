import 'package:dio/dio.dart';
import 'package:flutter/material.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:go_router/go_router.dart';
import 'package:tailtopia/core/storage/secure_storage.dart';
import 'package:tailtopia/features/vet/data/vet_repository.dart';
import 'package:tailtopia/features/vet/domain/vet_inbox_item.dart';
import 'package:tailtopia/features/vet/presentation/vet_request_detail_page.dart';
import 'package:tailtopia/l10n/app_localizations.dart';

/// 假兽医 repo：可控 session() 轮询状态与 accept() 结果（成功 / 409 已被抢）。
class _FakeVetRepository extends VetRepository {
  _FakeVetRepository({this.pollStatus = 'WAITING', this.acceptThrows409 = false})
      : super(dio: Dio(), tokenStore: InMemoryTokenStore());

  String pollStatus;
  bool acceptThrows409;
  int acceptCalls = 0;

  @override
  Future<VetSession> session(int sessionId) async =>
      VetSession(id: sessionId, status: pollStatus, source: 'AI_UPGRADE', hasAiContext: true);

  @override
  Future<VetSession> accept(int sessionId) async {
    acceptCalls++;
    if (acceptThrows409) {
      throw DioException(
        requestOptions: RequestOptions(path: '/accept'),
        response: Response(requestOptions: RequestOptions(path: '/accept'), statusCode: 409),
      );
    }
    return VetSession(id: sessionId, status: 'IN_PROGRESS', source: 'AI_UPGRADE', hasAiContext: true);
  }
}

const _item = VetInboxItem(
  sessionId: 5,
  source: 'AI_UPGRADE',
  aiDangerLevel: 'YELLOW',
  symptomPreview: '呕吐两次',
  imageCount: 2,
  waitingElapsedSeconds: 30,
);

Future<void> _pump(WidgetTester tester, _FakeVetRepository repo) async {
  final router = GoRouter(
    initialLocation: '/',
    routes: [
      GoRoute(
        path: '/',
        builder: (c, s) => Scaffold(
          body: Center(
            child: ElevatedButton(
              onPressed: () => c.push('/vet/request/5', extra: _item),
              child: const Text('go'),
            ),
          ),
        ),
      ),
      GoRoute(
        path: '/vet/request/:id',
        builder: (c, s) => VetRequestDetailPage(item: s.extra! as VetInboxItem),
      ),
      GoRoute(
        path: '/vet/conversation/:id',
        builder: (c, s) => Scaffold(body: Text('conversation ${s.pathParameters['id']}')),
      ),
    ],
  );
  await tester.pumpWidget(ProviderScope(
    overrides: [vetRepositoryProvider.overrideWithValue(repo)],
    child: MaterialApp.router(
      localizationsDelegates: AppLocalizations.localizationsDelegates,
      supportedLocales: AppLocalizations.supportedLocales,
      locale: const Locale('en'),
      routerConfig: router,
    ),
  ));
  await tester.pumpAndSettle();
  await tester.tap(find.text('go'));
  await tester.pumpAndSettle();
}

void main() {
  testWidgets('AC5: 进入即展示请求详情 + 3 分钟预览倒计时', (tester) async {
    await _pump(tester, _FakeVetRepository());
    expect(find.text('呕吐两次'), findsOneWidget); // symptomPreview 在 AI 评估框正文
    expect(find.text('SYMPTOM PHOTOS (2)'), findsOneWidget);
    // 倒计时框可见；走过 1 秒后显示裸 MM:SS = 02:5x。
    await tester.pump(const Duration(seconds: 1));
    final countdown = tester.widget<Text>(find.byKey(const ValueKey('vetPreviewCountdown')));
    expect(countdown.data, startsWith('02:5'));
  });

  testWidgets('AC5 接单成功 → 进会话页', (tester) async {
    final repo = _FakeVetRepository();
    await _pump(tester, repo);
    await tester.tap(find.byKey(const ValueKey('vetRequestAccept')));
    await tester.pumpAndSettle();
    expect(repo.acceptCalls, 1);
    expect(find.text('conversation 5'), findsOneWidget);
  });

  testWidgets('AC5 状态2：接单 409（被他人抢）→ 提示已被接单 + 返回列表', (tester) async {
    final repo = _FakeVetRepository(acceptThrows409: true);
    await _pump(tester, repo);
    await tester.tap(find.byKey(const ValueKey('vetRequestAccept')));
    await tester.pump(); // 触发 snackbar
    expect(find.text('Already taken by another vet'), findsWidgets);
    await tester.pumpAndSettle();
    expect(find.text('go'), findsOneWidget); // 已返回列表
    await tester.pump(const Duration(seconds: 3)); // 走完 toast 定时器
  });

  testWidgets('AC5 状态1：预览期用户取消（轮询 CANCELLED）→ 此请求已关闭 + 返回', (tester) async {
    final repo = _FakeVetRepository(pollStatus: 'CANCELLED');
    await _pump(tester, repo);
    await tester.pump(VetRequestDetailPage.pollInterval); // 触发一次轮询
    await tester.pump();
    expect(find.text('This request has been closed'), findsOneWidget);
    await tester.pumpAndSettle();
    expect(find.text('go'), findsOneWidget);
    await tester.pump(const Duration(seconds: 3)); // 走完 toast 定时器
  });

  testWidgets('AC5 状态2：预览期他人接单（轮询非 WAITING）→ 已被接单 + 返回', (tester) async {
    final repo = _FakeVetRepository(pollStatus: 'IN_PROGRESS');
    await _pump(tester, repo);
    await tester.pump(VetRequestDetailPage.pollInterval);
    await tester.pump();
    expect(find.text('Already taken by another vet'), findsOneWidget);
    await tester.pumpAndSettle();
    expect(find.text('go'), findsOneWidget);
    await tester.pump(const Duration(seconds: 3)); // 走完 toast 定时器
  });

  testWidgets('AC5 状态3：3 分钟预览未操作 → 自动返回列表', (tester) async {
    await _pump(tester, _FakeVetRepository());
    expect(find.byKey(const ValueKey('vetPreviewCountdown')), findsOneWidget);
    await tester.pump(VetRequestDetailPage.previewWindow);
    await tester.pump();
    expect(find.text('Preview time ended, back to the list'), findsOneWidget);
    await tester.pumpAndSettle();
    expect(find.text('go'), findsOneWidget);
    await tester.pump(const Duration(seconds: 3)); // 走完 toast 定时器
  });
}
