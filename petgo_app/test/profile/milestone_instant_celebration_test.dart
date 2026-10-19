import 'dart:async';

import 'package:flutter/material.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:go_router/go_router.dart';
import 'package:tailtopia/core/router/deep_link_routes.dart';
import 'package:tailtopia/features/profile/data/milestone_celebration_reporter.dart';
import 'package:tailtopia/features/profile/data/milestone_repository.dart';
import 'package:tailtopia/features/profile/domain/milestone.dart';
import 'package:tailtopia/features/profile/domain/milestone_instant_codes.dart';
import 'package:tailtopia/features/profile/presentation/milestone_instant_celebration.dart';
import 'package:tailtopia/features/profile/presentation/widgets/milestone_badge.dart';
import 'package:tailtopia/l10n/app_localizations.dart';

import 'milestone_instant_fakes.dart';
import 'package:tailtopia/features/profile/data/current_pet_provider.dart';

/// V1.3.4 Story 4.3 · AC1 / AC3 / AC4：当场庆祝 helper 的预读 + 短轮询 + 兜底。
class _Host extends ConsumerStatefulWidget {
  const _Host();

  @override
  ConsumerState<_Host> createState() => _HostState();
}

/// helper 抛出的异常（宿主 dispose 后 State 已不在，故放顶层）。
Object? _hostError;

class _HostState extends ConsumerState<_Host> {
  @override
  Widget build(BuildContext context) => Scaffold(
        body: Center(
          child: TextButton(
            key: const ValueKey('go'),
            onPressed: () => celebrateAutoMilestoneIfAny(context, ref, kPlaceCheckinMilestoneCodes)
                .catchError((Object e) => _hostError = e),
            child: const Text('host'),
          ),
        ),
      );
}

const _zeros = [Duration.zero, Duration.zero, Duration.zero];

Future<ProviderContainer> _pump(WidgetTester tester, ScriptedMilestoneRepository repo,
    {List<Duration> delays = _zeros}) async {
  final container = ProviderContainer(overrides: [
    milestoneRepositoryProvider.overrideWithValue(repo),
    milestoneInstantPollDelaysProvider.overrideWithValue(delays),
    // V1.4.0 multi-pet：里程碑列表按当前宠物分族。
    effectivePetIdProvider.overrideWithValue(const AsyncData(1)),
  ]);
  addTearDown(container.dispose);
  await tester.pumpWidget(UncontrolledProviderScope(
    container: container,
    child: const MaterialApp(
      locale: Locale('id'),
      localizationsDelegates: AppLocalizations.localizationsDelegates,
      supportedLocales: AppLocalizations.supportedLocales,
      home: _Host(),
    ),
  ));
  return container;
}

/// 庆祝页彩纸持续动画 → 不能 pumpAndSettle；有限次 pump 让预读 / 轮询 / 弹层转场走完。
Future<void> _run(WidgetTester tester) async {
  await tester.tap(find.byKey(const ValueKey('go')));
  for (var i = 0; i < 10; i++) {
    await tester.pump(const Duration(milliseconds: 100));
  }
}

final _celebration = find.byKey(const ValueKey('milestoneCelebration'));

void main() {
  setUp(() {
    MilestoneBadgeAssets.debugOverride = const {};
    _hostError = null;
  });
  tearDown(() => MilestoneBadgeAssets.debugOverride = null);

  testWidgets('预读未完成 → 轮询到第 2 次重读才完成未庆祝 → 弹出；关闭后宿主仍在，回报 C-S17', (tester) async {
    final repo = ScriptedMilestoneRepository([
      instantMilestoneList('C-S17', completed: false), // 预读
      instantMilestoneList('C-S17', completed: false), // 第 1 次轮询
      instantMilestoneList('C-S17', completed: true), // 第 2 次轮询
    ]);
    final c = await _pump(tester, repo);
    await _run(tester);

    expect(_celebration, findsOneWidget);
    expect(find.text('Check-in pertama Momo! 📍'), findsOneWidget);
    expect(repo.calls, 3, reason: '命中即停，不再跑第 3 次轮询');
    expect(c.read(locallyCelebratedMilestonesProvider), contains('C-S17'), reason: '弹出前先记本地');
    expect(repo.reported, isEmpty, reason: '回报在关闭后才发');

    // 右上角关闭钮在 800×600 测试画布上被居中滚动区盖住（命中测试打不到），直接触发其回调。
    tester.widget<IconButton>(find.byKey(const ValueKey('milestoneCelebrationClose'))).onPressed!();
    await tester.pumpAndSettle();
    expect(_celebration, findsNothing);
    expect(find.text('host'), findsOneWidget);
    expect(repo.reported, [
      ['C-S17']
    ]);
  });

  testWidgets('预读即「已完成且已庆祝」→ 不弹，只读 1 次', (tester) async {
    final repo = ScriptedMilestoneRepository([instantMilestoneList('C-S17', completed: true, celebrated: true)]);
    await _pump(tester, repo);
    await _run(tester);
    await tester.pumpAndSettle();
    expect(_celebration, findsNothing);
    expect(repo.calls, 1);
  });

  testWidgets('预读不含目标 code → 不弹，只读 1 次', (tester) async {
    final repo = ScriptedMilestoneRepository([instantMilestoneList('C-S5', completed: true)]);
    await _pump(tester, repo);
    await _run(tester);
    await tester.pumpAndSettle();
    expect(_celebration, findsNothing);
    expect(repo.calls, 1);
  });

  testWidgets('三次轮询都未完成 → 不弹，读 4 次，无挂起计时器', (tester) async {
    final repo = ScriptedMilestoneRepository([instantMilestoneList('C-S17', completed: false)]);
    await _pump(tester, repo);
    await _run(tester);
    await tester.pumpAndSettle();
    expect(_celebration, findsNothing);
    expect(repo.calls, 4);
    expect(repo.reported, isEmpty);
  });

  testWidgets('读取抛错 → 不弹、不抛', (tester) async {
    final repo = ScriptedMilestoneRepository([StateError('offline')]);
    await _pump(tester, repo);
    await _run(tester);
    await tester.pumpAndSettle();
    expect(_celebration, findsNothing);
    expect(_hostError, isNull);
    expect(repo.calls, 1);
  });

  testWidgets('轮询中途读取抛错 → 静默结束，不再继续轮询', (tester) async {
    final repo = ScriptedMilestoneRepository([
      instantMilestoneList('C-S17', completed: false),
      StateError('offline'),
      instantMilestoneList('C-S17', completed: true),
    ]);
    await _pump(tester, repo);
    await _run(tester);
    await tester.pumpAndSettle();
    expect(_celebration, findsNothing);
    expect(repo.calls, 2);
  });

  testWidgets('预读即「完成未庆祝」→ 直接弹，只读 1 次', (tester) async {
    final repo = ScriptedMilestoneRepository([instantMilestoneList('C-S17', completed: true)]);
    await _pump(tester, repo);
    await _run(tester);
    expect(_celebration, findsOneWidget);
    expect(repo.calls, 1);
    tester.widget<IconButton>(find.byKey(const ValueKey('milestoneCelebrationClose'))).onPressed!();
    await tester.pumpAndSettle();
  });

  testWidgets('本机已庆祝集合里有该 code → 不弹（预读即结束）', (tester) async {
    final repo = ScriptedMilestoneRepository([instantMilestoneList('C-S17', completed: true)]);
    final c = await _pump(tester, repo);
    c.read(locallyCelebratedMilestonesProvider.notifier).add(['C-S17']);
    await _run(tester);
    await tester.pumpAndSettle();
    expect(_celebration, findsNothing);
    expect(repo.calls, 1);
  });

  testWidgets('轮询间隔覆盖为 const [] → 完全不读里程碑', (tester) async {
    final repo = ScriptedMilestoneRepository([instantMilestoneList('C-S17', completed: true)]);
    await _pump(tester, repo, delays: const []);
    await _run(tester);
    await tester.pumpAndSettle();
    expect(_celebration, findsNothing);
    expect(repo.calls, 0);
  });

  testWidgets('首次命中「完成未庆祝」时调用次数 = 预读 1 + 轮询 2（与 story 测试 1 一致）', (tester) async {
    // 与第一个用例同脚本，单独钉住读取次数，防止以后改成「先轮询后预读」等口径漂移。
    final repo = ScriptedMilestoneRepository([
      instantMilestoneList('C-S17', completed: false),
      instantMilestoneList('C-S17', completed: false),
      instantMilestoneList('C-S17', completed: true),
    ]);
    await _pump(tester, repo);
    await _run(tester);
    expect(repo.calls, 3);
    tester.widget<IconButton>(find.byKey(const ValueKey('milestoneCelebrationClose'))).onPressed!();
    await tester.pumpAndSettle();
  });

  testWidgets('复审 #1：轮询期间宿主页被 push 盖住 → 不弹（不盖到新页上），交补庆祝', (tester) async {
    final repo = ScriptedMilestoneRepository([
      instantMilestoneList('C-S17', completed: false), // 预读
      instantMilestoneList('C-S17', completed: true), // 轮询命中时宿主已不是当前路由
    ]);
    final c = await _pump(tester, repo, delays: const [Duration(milliseconds: 300)]);
    await tester.tap(find.byKey(const ValueKey('go')));
    await tester.pump(); // 预读完成，进入 300ms 等待
    Navigator.of(tester.element(find.byType(_Host)))
        .push(MaterialPageRoute<void>(builder: (_) => const Scaffold(body: Text('next'))));
    await tester.pumpAndSettle();
    await tester.pump(const Duration(milliseconds: 500));
    await tester.pumpAndSettle();
    expect(find.text('next'), findsOneWidget);
    expect(_celebration, findsNothing);
    expect(repo.calls, 2);
    expect(c.read(locallyCelebratedMilestonesProvider), isEmpty, reason: '没弹就不记本地，补庆祝照常');
    expect(repo.reported, isEmpty);
    expect(_hostError, isNull);
  });

  testWidgets('复审 #4：轮询期间宿主页 dispose → 不弹、不抛', (tester) async {
    final repo = ScriptedMilestoneRepository([
      instantMilestoneList('C-S17', completed: false),
      instantMilestoneList('C-S17', completed: true),
    ]);
    final c = await _pump(tester, repo, delays: const [Duration(milliseconds: 300)]);
    await tester.tap(find.byKey(const ValueKey('go')));
    await tester.pump();
    // 换掉整棵树：宿主 State dispose（容器仍在）。
    await tester.pumpWidget(UncontrolledProviderScope(
      container: c,
      child: const MaterialApp(home: Scaffold(body: Text('gone'))),
    ));
    await tester.pump(const Duration(milliseconds: 500));
    await tester.pumpAndSettle();
    expect(find.text('gone'), findsOneWidget);
    expect(_celebration, findsNothing);
    expect(repo.calls, 1, reason: '等待结束时宿主已销毁 → 不再重读');
    expect(_hostError, isNull);
  });

  testWidgets('复审 #4：单次读取超过 2s → 静默结束，不弹、不再轮询', (tester) async {
    final repo = ScriptedMilestoneRepository([Completer<MilestoneList>().future]);
    await _pump(tester, repo);
    await tester.tap(find.byKey(const ValueKey('go')));
    await tester.pump(const Duration(milliseconds: 1900));
    expect(repo.calls, 1);
    await tester.pump(const Duration(milliseconds: 200)); // 越过 2s 超时
    await tester.pumpAndSettle();
    expect(_celebration, findsNothing);
    expect(repo.calls, 1);
    expect(_hostError, isNull);
  });

  testWidgets('复审 #2：列表 provider 残留旧错误 → 先失效再读，照常检测并弹出', (tester) async {
    final repo = ScriptedMilestoneRepository([
      StateError('早先断网'),
      instantMilestoneList('C-S17', completed: true),
    ]);
    final c = await _pump(tester, repo);
    await expectLater(c.read(milestoneListProvider(1).future), throwsA(isA<StateError>()));
    expect(c.read(milestoneListProvider(1)).hasError, isTrue);
    await _run(tester);
    expect(_celebration, findsOneWidget);
    expect(repo.calls, 2);
    tester.widget<IconButton>(find.byKey(const ValueKey('milestoneCelebrationClose'))).onPressed!();
    await tester.pumpAndSettle();
  });

  testWidgets('复审 #4：「Lihat semua milestone」→ push /profile/milestones，extra 带该 code；返回回到宿主',
      (tester) async {
    Object? extra;
    final router = GoRouter(routes: [
      GoRoute(path: '/', builder: (_, _) => const _Host()),
      GoRoute(
        path: DeepLinkRoutes.milestoneList,
        builder: (_, s) {
          extra = s.extra;
          return const Scaffold(body: Text('milestone-list'));
        },
      ),
    ]);
    addTearDown(router.dispose);
    final repo = ScriptedMilestoneRepository([instantMilestoneList('C-S17', completed: true)]);
    final container = ProviderContainer(overrides: [
      milestoneRepositoryProvider.overrideWithValue(repo),
      milestoneInstantPollDelaysProvider.overrideWithValue(_zeros),
      effectivePetIdProvider.overrideWithValue(const AsyncData(1)),
    ]);
    addTearDown(container.dispose);
    await tester.pumpWidget(UncontrolledProviderScope(
      container: container,
      child: MaterialApp.router(
        routerConfig: router,
        locale: const Locale('id'),
        localizationsDelegates: AppLocalizations.localizationsDelegates,
        supportedLocales: AppLocalizations.supportedLocales,
      ),
    ));
    await _run(tester);
    expect(_celebration, findsOneWidget);
    await tester.tap(find.byKey(const ValueKey('milestoneCelebrateSeeAll')));
    await tester.pumpAndSettle();
    expect(find.text('milestone-list'), findsOneWidget);
    expect(extra, <String>{'C-S17'});
    expect(router.routerDelegate.currentConfiguration.matches.length, 2, reason: 'push 而非 go：栈里仍有宿主');
    router.pop();
    await tester.pumpAndSettle();
    expect(find.text('host'), findsOneWidget);
  });

  test('默认轮询间隔为健康页范式 500 / 800 / 1200 ms', () {
    final c = ProviderContainer();
    addTearDown(c.dispose);
    expect(c.read(milestoneInstantPollDelaysProvider), const [
      Duration(milliseconds: 500),
      Duration(milliseconds: 800),
      Duration(milliseconds: 1200),
    ]);
  });
}
