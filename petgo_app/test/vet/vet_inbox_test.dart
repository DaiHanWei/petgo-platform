import 'package:dio/dio.dart';
import 'package:flutter/material.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:tailtopia/core/storage/secure_storage.dart';
import 'package:tailtopia/features/vet/data/vet_repository.dart';
import 'package:tailtopia/features/vet/domain/vet_login_response.dart';
import 'package:tailtopia/features/vet/domain/vet_queue.dart';
import 'package:tailtopia/features/vet/domain/vet_workbench_lists.dart';
import 'package:tailtopia/features/vet/presentation/vet_inbox_page.dart';
import 'package:tailtopia/l10n/app_localizations.dart';

/// Story 3.6：兽医待接单 Tab 改造为计费队列（`vetQueue()`）。覆盖：available 接单 CTA、接单 409 Toast、
/// awaitingPay 倒计时中间态（FR-53A）、待支付项消失→成交/未成交 Toast（FR-53B，经刷新驱动跃迁）。
class _FakeVetRepository extends VetRepository {
  _FakeVetRepository({VetQueue? queue})
      : _queue = queue ?? const VetQueue(),
        super(dio: Dio(), tokenStore: InMemoryTokenStore());

  VetQueue _queue;
  List<VetActiveItem> activeItems = const [];
  bool acceptThrows = false;
  String? acceptedToken;

  void setQueue(VetQueue q) => _queue = q;

  @override
  Future<VetMe> me() async => const VetMe(id: 1, displayName: '王医生', status: 'ACTIVE');

  @override
  Future<List<VetHistoryEntry>> history() async => const [];

  @override
  Future<bool> readOnlineStatus() async => false;

  @override
  Future<void> heartbeat() async {}

  @override
  Future<VetQueue> vetQueue() async => _queue;

  @override
  Future<List<VetActiveItem>> activeSessions() async => activeItems;

  @override
  Future<void> acceptConsultRequest(String requestToken) async {
    if (acceptThrows) {
      throw DioException(requestOptions: RequestOptions(path: 'x'), response: Response(requestOptions: RequestOptions(path: 'x'), statusCode: 409));
    }
    acceptedToken = requestToken;
  }
}

Future<void> _pump(WidgetTester tester, _FakeVetRepository repo) async {
  await tester.pumpWidget(ProviderScope(
    overrides: [vetRepositoryProvider.overrideWithValue(repo)],
    child: const MaterialApp(
      localizationsDelegates: AppLocalizations.localizationsDelegates,
      supportedLocales: AppLocalizations.supportedLocales,
      locale: Locale('en'),
      home: VetInboxPage(),
    ),
  ));
  // Inbox 有周期轮询 Timer（永不 settle）→ 用 pump 数帧刷新异步加载（照 vet_workbench_test）。
  await tester.pump(); // 完成首帧 + vetQueue future
  await tester.pump(const Duration(milliseconds: 100));
}

/// 让 3s toast 的静态计时器烧完并清理浮层（否则「Timer still pending」）。
Future<void> _drainToast(WidgetTester tester) async {
  await tester.pump(const Duration(seconds: 4));
  await tester.pump();
}

VetQueueItem _item(String token, {String? petName, String? species, int? age, String? owner, int wait = 10}) =>
    VetQueueItem(
      requestToken: token,
      petName: petName,
      petSpecies: species,
      petAgeMonths: age,
      ownerHandle: owner,
      waitingSeconds: wait,
    );

void main() {
  testWidgets('空队列 → 分区头 (0) + 空态', (tester) async {
    await _pump(tester, _FakeVetRepository());
    expect(find.text('CURRENT QUEUE (0)'), findsOneWidget);
    expect(find.text('No incoming requests'), findsOneWidget);
  });

  testWidgets('available 渲染计费队列卡（身份 + meta）+ 接单 CTA', (tester) async {
    final repo = _FakeVetRepository(
      queue: VetQueue(available: [_item('req-5', petName: 'Oyen', species: 'CAT', age: 24, owner: 'rani')]),
    );
    await _pump(tester, repo);

    expect(find.byKey(const ValueKey('vetQueueCard_req-5')), findsOneWidget);
    expect(find.text('Oyen'), findsOneWidget);
    expect(find.text('Cat · 2 yr · @rani'), findsOneWidget);
    expect(find.byKey(const ValueKey('vetAccept_req-5')), findsOneWidget);
    expect(find.text('Accept'), findsOneWidget);
    // 计费流无 AI 摘要/危险徽章
    expect(find.text('AI SUMMARY'), findsNothing);
  });

  testWidgets('点接单成功 → 调 acceptConsultRequest', (tester) async {
    final repo = _FakeVetRepository(queue: VetQueue(available: [_item('req-7')]));
    await _pump(tester, repo);

    await tester.tap(find.byKey(const ValueKey('vetAccept_req-7')));
    await tester.pump(); // acceptConsultRequest future
    await tester.pump(); // 后续 _reloadQueue
    expect(repo.acceptedToken, 'req-7');
  });

  testWidgets('接单 409 → 3s Toast（被抢/占用）', (tester) async {
    final repo = _FakeVetRepository(queue: VetQueue(available: [_item('req-8')]))..acceptThrows = true;
    await _pump(tester, repo);

    await tester.tap(find.byKey(const ValueKey('vetAccept_req-8')));
    await tester.pump(); // acceptConsultRequest 抛错
    await tester.pump(); // Toast overlay 插入
    expect(find.text('This request was already taken or has expired'), findsOneWidget);
    await _drainToast(tester);
  });

  testWidgets('FR-53A: awaitingPay → 顶部等待支付倒计时卡（无接单池）', (tester) async {
    final deadline = DateTime.now().toUtc().add(const Duration(seconds: 75));
    final repo = _FakeVetRepository(
      queue: VetQueue(awaitingPay: VetAwaitingPay(requestToken: 'req-p', petName: '阿黄', payDeadlineAt: deadline)),
    );
    await _pump(tester, repo);

    expect(find.byKey(const ValueKey('vetAwaitingPayCard')), findsOneWidget);
    expect(find.text('Waiting for payment'), findsOneWidget);
    final countdown = tester.widget<Text>(find.byKey(const ValueKey('vetAwaitingPayCountdown')));
    expect(countdown.data, matches(RegExp(r'^\d{2}:\d{2}$'))); // 服务端权威倒计时渲染 MM:SS
    expect(countdown.data, startsWith('01:1')); // ~75s 剩余（01:1x，含渲染耗时容差）
    // 忙时不显空态占位
    expect(find.text('No incoming requests'), findsNothing);
  });

  testWidgets('FR-53A: pausedAt → 暂停显示（A-4 跳充值）', (tester) async {
    final repo = _FakeVetRepository(
      queue: VetQueue(
        awaitingPay: VetAwaitingPay(
          requestToken: 'req-pz',
          payDeadlineAt: DateTime.now().toUtc().add(const Duration(seconds: 60)),
          pausedAt: DateTime.now().toUtc(),
        ),
      ),
    );
    await _pump(tester, repo);
    expect(find.text('User is topping up…'), findsOneWidget);
    expect(find.byKey(const ValueKey('vetAwaitingPayCountdown')), findsNothing); // 暂停不显倒计时
  });

  testWidgets('FR-53B: 待支付项消失 + 有新会话 → 成交 Toast', (tester) async {
    final repo = _FakeVetRepository(
      queue: VetQueue(awaitingPay: VetAwaitingPay(requestToken: 'req-x', payDeadlineAt: DateTime.now().toUtc().add(const Duration(seconds: 60)))),
    );
    await _pump(tester, repo);
    // 模拟支付成功：awaitingPay 消失 + 出现进行中会话。
    repo.setQueue(const VetQueue());
    repo.activeItems = const [VetActiveItem(sessionId: 1, userId: 2, petName: 'p', source: 'DIRECT')];

    await tester.tap(find.byKey(const ValueKey('vetInboxRefresh'))); // 驱动一次轮询
    await tester.pump(); // vetQueue future
    await tester.pump(); // activeSessions future + Toast overlay
    expect(find.text('User has paid, the session has started'), findsOneWidget);
    await _drainToast(tester);
  });

  testWidgets('FR-53B: 待支付项消失 + 无新会话 → 未成交 Toast（取消/超时/未支付）', (tester) async {
    final repo = _FakeVetRepository(
      queue: VetQueue(awaitingPay: VetAwaitingPay(requestToken: 'req-y', payDeadlineAt: DateTime.now().toUtc().add(const Duration(seconds: 60)))),
    );
    await _pump(tester, repo);
    repo.setQueue(const VetQueue()); // awaitingPay 消失，无新会话
    repo.activeItems = const [];

    await tester.tap(find.byKey(const ValueKey('vetInboxRefresh')));
    await tester.pump(); // vetQueue future
    await tester.pump(); // activeSessions future + Toast overlay
    expect(find.text('Order cancelled or payment timed out'), findsOneWidget);
    await _drainToast(tester);
  });
}
