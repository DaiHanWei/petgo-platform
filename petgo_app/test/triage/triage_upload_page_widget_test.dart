import 'dart:async';

import 'package:flutter/material.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:go_router/go_router.dart';
import 'package:shared_preferences/shared_preferences.dart';
import 'package:tailtopia/features/profile/data/pet_list_repository.dart';
import 'package:tailtopia/features/profile/data/profile_repository.dart';
import 'package:tailtopia/features/triage/data/triage_repository.dart';
import 'package:tailtopia/features/triage/domain/triage_result_controller.dart';
import 'package:tailtopia/features/triage/domain/triage_upload_controller.dart';
import 'package:tailtopia/features/triage/presentation/triage_upload_page.dart';
import 'package:tailtopia/l10n/app_localizations.dart';
import 'package:tailtopia/shared/widgets/red_alert_overlay.dart';

class _FakeTriageRepo implements TriageRepository {
  _FakeTriageRepo(this.results);
  final List<TriageResult> results;
  int calls = 0;

  @override
  Future<int> submitTriage({
    String? symptomText,
    List<String> imageObjectKeys = const <String>[],
    int? petId,
    String? idempotencyKey,
  }) async =>
      1;

  @override
  Future<TriageResult> pollTriage(int triageId) async {
    final r = results[calls < results.length ? calls : results.length - 1];
    calls++;
    return r;
  }

  @override
  Future<UnlockResult> unlockTriage(int triageId, UnlockMethod method) async =>
      throw UnimplementedError();

  @override
  Future<FreeQuotaView> fetchFreeQuota() async => throw UnimplementedError();
}

ProviderContainer _container(_FakeTriageRepo repo) {
  final c = ProviderContainer(overrides: [
    triageRepositoryProvider.overrideWithValue(repo),
    triagePollIntervalProvider.overrideWithValue(const Duration(milliseconds: 1)),
    triageTimeoutProvider.overrideWithValue(const Duration(milliseconds: 120)),
    petProfileProvider.overrideWith((ref) => null), // 红色 overlay 读档案不打网络
  ]);
  addTearDown(c.dispose);
  return c;
}

Future<void> _pump(WidgetTester tester, ProviderContainer c) async {
  // ai-upload.html 重做后表单较长（速度横幅+分段label），用高视口确保提交钮不出 fold。
  await tester.binding.setSurfaceSize(const Size(440, 1600));
  addTearDown(() => tester.binding.setSurfaceSize(null));
  await tester.pumpWidget(UncontrolledProviderScope(
    container: c,
    child: const MaterialApp(
      localizationsDelegates: AppLocalizations.localizationsDelegates,
      supportedLocales: AppLocalizations.supportedLocales,
      home: TriageUploadPage(),
    ),
  ));
  await tester.pumpAndSettle();
}

void main() {
  testWidgets('AC2/AC3: 初始表单 + 提交按钮在无输入时禁用', (tester) async {
    final c = _container(_FakeTriageRepo([const TriageResult(status: TriageStatus.done)]));
    await _pump(tester, c);
    expect(find.byKey(const ValueKey('triageSymptomField')), findsOneWidget);
    expect(find.byKey(const ValueKey('triageAddImage')), findsOneWidget);
    final submit = tester.widget<FilledButton>(find.byKey(const ValueKey('triageSubmit')));
    expect(submit.onPressed, isNull); // 无输入禁用
  });

  testWidgets('AC5: 仅文字、无图 → 提交按钮可用（图片选填）', (tester) async {
    final c = _container(_FakeTriageRepo([const TriageResult(status: TriageStatus.done)]));
    await _pump(tester, c);
    // 仅填文字、不加任何图片。
    await tester.enterText(find.byKey(const ValueKey('triageSymptomField')), '只是有点没精神');
    await tester.pump();
    final submit = tester.widget<FilledButton>(find.byKey(const ValueKey('triageSubmit')));
    expect(submit.onPressed, isNotNull); // 文字非空即可提交，不依赖图片 widget
  });

  testWidgets('AC4: 服务异常(FAILED) → 异常态 + 软引导兽医', (tester) async {
    final c = _container(_FakeTriageRepo([const TriageResult(status: TriageStatus.failed)]));
    await _pump(tester, c);
    c.read(triageUploadProvider.notifier).setSymptom('误食巧克力');
    await c.read(triageUploadProvider.notifier).submit();
    await tester.pumpAndSettle();
    expect(find.byKey(const ValueKey('triageError')), findsOneWidget);
    expect(find.byKey(const ValueKey('triageContactVet')), findsOneWidget);
    expect(find.byKey(const ValueKey('triageResubmit')), findsOneWidget);
  });

  testWidgets('AC4: 一直处理中 → 超时态 + 重新提交', (tester) async {
    final c = _container(_FakeTriageRepo([const TriageResult(status: TriageStatus.processing)]));
    await _pump(tester, c);
    c.read(triageUploadProvider.notifier).setSymptom('x');
    // 轮询含 Future.delayed —— testWidgets 假时钟不推进真实定时器，用 runAsync 跑真实事件循环。
    await tester.runAsync(() => c.read(triageUploadProvider.notifier).submit());
    await tester.pumpAndSettle();
    expect(find.byKey(const ValueKey('triageTimeout')), findsOneWidget);
    expect(find.byKey(const ValueKey('triageResubmit')), findsOneWidget);
  });

  testWidgets('AC3/F5: DONE(黄) → 渲染黄色结果页（4.4 三态卡）', (tester) async {
    final c = _container(_FakeTriageRepo(
        [const TriageResult(status: TriageStatus.done, dangerLevel: DangerLevel.yellow)]));
    await _pump(tester, c);
    c.read(triageUploadProvider.notifier).setSymptom('x');
    await c.read(triageUploadProvider.notifier).submit();
    await tester.pumpAndSettle();
    expect(find.byKey(const ValueKey('triageYellowPage')), findsOneWidget);
  });

  testWidgets('F5/4.5: DONE(红) → 自底滑起红色半屏强提醒（不渲染绿/黄页）', (tester) async {
    final c = _container(_FakeTriageRepo(
        [const TriageResult(status: TriageStatus.done, dangerLevel: DangerLevel.red)]));
    await _pump(tester, c);
    c.read(triageUploadProvider.notifier).setSymptom('x');
    await c.read(triageUploadProvider.notifier).submit();
    await tester.pump(); // 构建红色结果 + 排程 overlay
    await tester.pump(const Duration(milliseconds: 350)); // 半屏滑起
    expect(find.byType(RedAlertOverlay), findsOneWidget);
    expect(find.byKey(const ValueKey('triageYellowPage')), findsNothing);
    expect(find.byKey(const ValueKey('triageGreenPage')), findsNothing);
    await tester.pump(const Duration(seconds: 5)); // 倒计时结束，清理定时器
  });

  // ---- V1.4.0 multi-pet Story 4.1 · AC7.3：路由 petId 写草稿；缺失时补走一次选择 ----
  group('Story 4.1 上传页所选宠物', () {
    const two = [PetListItem(id: 7, name: 'Momo', isDefault: true), PetListItem(id: 8, name: 'Kopi')];

    Future<ProviderContainer> pumpPushed(WidgetTester tester, {int? petId, required List<PetListItem> pets}) async {
      SharedPreferences.setMockInitialValues({});
      final c = ProviderContainer(overrides: [
        triageRepositoryProvider.overrideWithValue(_FakeTriageRepo([const TriageResult(status: TriageStatus.done)])),
        petProfileProvider.overrideWith((ref) => null),
        petListProvider.overrideWith((ref) async => pets),
      ]);
      addTearDown(c.dispose);
      await tester.binding.setSurfaceSize(const Size(440, 1600));
      addTearDown(() => tester.binding.setSurfaceSize(null));
      await tester.pumpWidget(UncontrolledProviderScope(
        container: c,
        child: MaterialApp(
          localizationsDelegates: AppLocalizations.localizationsDelegates,
          supportedLocales: AppLocalizations.supportedLocales,
          locale: const Locale('id'),
          home: Builder(
            builder: (context) => Scaffold(
              body: ElevatedButton(
                key: const ValueKey('open'),
                onPressed: () => Navigator.of(context)
                    .push(MaterialPageRoute<void>(builder: (_) => TriageUploadPage(petId: petId))),
                child: const Text('open'),
              ),
            ),
          ),
        ),
      ));
      await tester.tap(find.byKey(const ValueKey('open')));
      await tester.pumpAndSettle();
      return c;
    }

    testWidgets('带 ?petId= → 首帧重置后写入草稿，不弹选择', (tester) async {
      final c = await pumpPushed(tester, petId: 8, pets: two);
      expect(find.text('Cek gejala untuk siapa?'), findsNothing);
      expect(c.read(triageUploadProvider).petId, 8);
    });

    testWidgets('缺 petId + ≥2 只 → 补弹 H1，选中后写草稿', (tester) async {
      final c = await pumpPushed(tester, pets: two);
      expect(find.text('Cek gejala untuk siapa?'), findsOneWidget);
      await tester.tap(find.byKey(const ValueKey('petPickerRow-8')));
      await tester.pump();
      await tester.tap(find.byKey(const ValueKey('petPickerConfirm')));
      await tester.pumpAndSettle();
      expect(c.read(triageUploadProvider).petId, 8);
      expect(find.byKey(const ValueKey('triageSymptomField')), findsOneWidget);
    });

    testWidgets('缺 petId + ≥2 只 + 取消 → 退出上传页', (tester) async {
      await pumpPushed(tester, pets: two);
      await tester.tapAt(const Offset(10, 10));
      await tester.pumpAndSettle();
      expect(find.byKey(const ValueKey('triageSymptomField')), findsNothing);
      expect(find.byKey(const ValueKey('open')), findsOneWidget);
    });

    testWidgets('缺 petId + 1 只 → 自动用那只', (tester) async {
      final c = await pumpPushed(tester, pets: const [PetListItem(id: 7, name: 'Momo')]);
      expect(find.text('Cek gejala untuk siapa?'), findsNothing);
      expect(c.read(triageUploadProvider).petId, 7);
    });
  });

  // ---- code review 4-1 A2 / A3 ----
  testWidgets('A2: 登录续跳（go 进来、栈里只有上传页）+ ≥2 只 + 取消 → go 回问诊 Tab', (tester) async {
    SharedPreferences.setMockInitialValues({});
    final c = ProviderContainer(overrides: [
      triageRepositoryProvider.overrideWithValue(_FakeTriageRepo([const TriageResult(status: TriageStatus.done)])),
      petProfileProvider.overrideWith((ref) => null),
      petListProvider.overrideWith((ref) async =>
          const [PetListItem(id: 7, name: 'Momo', isDefault: true), PetListItem(id: 8, name: 'Kopi')]),
    ]);
    addTearDown(c.dispose);
    final router = GoRouter(initialLocation: '/triage/upload', routes: [
      GoRoute(path: '/triage', builder: (_, _) => const Scaffold(body: Text('triage-tab'))),
      GoRoute(path: '/triage/upload', builder: (_, _) => const TriageUploadPage()),
    ]);
    await tester.binding.setSurfaceSize(const Size(440, 1600));
    addTearDown(() => tester.binding.setSurfaceSize(null));
    await tester.pumpWidget(UncontrolledProviderScope(
      container: c,
      child: MaterialApp.router(
        routerConfig: router,
        localizationsDelegates: AppLocalizations.localizationsDelegates,
        supportedLocales: AppLocalizations.supportedLocales,
        locale: const Locale('id'),
      ),
    ));
    await tester.pumpAndSettle();
    expect(find.text('Cek gejala untuk siapa?'), findsOneWidget);

    await tester.tapAt(const Offset(10, 10));
    await tester.pumpAndSettle();
    expect(find.text('triage-tab'), findsOneWidget);
  });

  testWidgets('A3: 所选宠物解析完成前提交按钮禁用（单宠物不会以 null 抢先提交）', (tester) async {
    SharedPreferences.setMockInitialValues({});
    final list = Completer<List<PetListItem>>();
    final c = ProviderContainer(overrides: [
      triageRepositoryProvider.overrideWithValue(_FakeTriageRepo([const TriageResult(status: TriageStatus.done)])),
      petProfileProvider.overrideWith((ref) => null),
      petListProvider.overrideWith((ref) => list.future),
    ]);
    addTearDown(c.dispose);
    await _pump(tester, c);
    await tester.enterText(find.byKey(const ValueKey('triageSymptomField')), 'muntah');
    await tester.pump();
    expect(tester.widget<FilledButton>(find.byKey(const ValueKey('triageSubmit'))).onPressed, isNull);

    list.complete(const [PetListItem(id: 7, name: 'Momo')]);
    await tester.pumpAndSettle();
    expect(tester.widget<FilledButton>(find.byKey(const ValueKey('triageSubmit'))).onPressed, isNotNull);
    expect(c.read(triageUploadProvider).petId, 7);
  });
}
