import 'package:dio/dio.dart';
import 'package:flutter/material.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:tailtopia/features/profile/data/profile_repository.dart';
import 'package:tailtopia/features/triage/data/triage_repository.dart';
import 'package:tailtopia/features/triage/domain/triage_unlock_controller.dart';
import 'package:tailtopia/features/triage/presentation/triage_result_view.dart';
import 'package:tailtopia/l10n/app_localizations.dart';

Future<void> _pump(WidgetTester tester, TriageResult result) async {
  final container = ProviderContainer(overrides: [
    petProfileProvider.overrideWith((ref) => null),
  ]);
  addTearDown(container.dispose);
  await tester.pumpWidget(UncontrolledProviderScope(
    container: container,
    child: MaterialApp(
      localizationsDelegates: AppLocalizations.localizationsDelegates,
      supportedLocales: AppLocalizations.supportedLocales,
      home: Scaffold(body: TriageResultView(result: result, triageId: 7)),
    ),
  ));
  await tester.pump();
  await tester.pump(const Duration(milliseconds: 350));
}

/// 可配置的假 repo（控制器测试用）。
class _FakeRepo implements TriageRepository {
  _FakeRepo({this.unlockResult, this.error});

  UnlockResult? unlockResult;
  Object? error;

  @override
  Future<UnlockResult> unlockTriage(int triageId, UnlockMethod method) async {
    if (error != null) throw error!;
    return unlockResult!;
  }

  @override
  Future<FreeQuotaView> fetchFreeQuota() async =>
      const FreeQuotaView(limit: 1, used: 0, remaining: 1);

  @override
  Future<int> submitTriage({
    String? symptomText,
    List<String> imageObjectKeys = const <String>[],
    int? petId,
    String? idempotencyKey,
  }) async =>
      7;

  @override
  Future<TriageResult> pollTriage(int triageId) async =>
      const TriageResult(status: TriageStatus.done);
}

DioException _dio409() => DioException(
      requestOptions: RequestOptions(path: '/x'),
      response: Response<dynamic>(requestOptions: RequestOptions(path: '/x'), statusCode: 409),
    );

void main() {
  group('TriageResult.isDetailLocked（红色永不锁模型层）', () {
    test('绿色 + locked → 锁定', () {
      expect(
          const TriageResult(status: TriageStatus.done, dangerLevel: DangerLevel.green, locked: true)
              .isDetailLocked,
          isTrue);
    });
    test('黄色 + locked → 锁定', () {
      expect(
          const TriageResult(status: TriageStatus.done, dangerLevel: DangerLevel.yellow, locked: true)
              .isDetailLocked,
          isTrue);
    });
    test('🔒 红色 + locked → 永不锁（头等）', () {
      expect(
          const TriageResult(status: TriageStatus.done, dangerLevel: DangerLevel.red, locked: true)
              .isDetailLocked,
          isFalse);
    });
    test('已解锁（locked=false）→ 不锁', () {
      expect(
          const TriageResult(status: TriageStatus.done, dangerLevel: DangerLevel.green, locked: false)
              .isDetailLocked,
          isFalse);
    });
    test('非 DONE → 不锁', () {
      expect(const TriageResult(status: TriageStatus.processing, locked: true).isDetailLocked,
          isFalse);
    });
  });

  group('paywall 渲染', () {
    testWidgets('黄色锁定 → 显 CTA + 锁定标题 + 骨架读屏隐藏；安全免责仍在', (tester) async {
      await _pump(
          tester,
          const TriageResult(
              status: TriageStatus.done,
              dangerLevel: DangerLevel.yellow,
              locked: true,
              disclaimer: 'AI 仅供参考'));
      expect(find.byKey(const ValueKey('triageUnlockCta')), findsOneWidget);
      expect(find.text('Detailed care advice is locked'), findsOneWidget);
      expect(find.byType(ExcludeSemantics), findsWidgets); // 骨架占位读屏隐藏
      expect(find.text('AI 仅供参考'), findsOneWidget); // 安全免费部分（免责）仍下发
    });

    testWidgets('绿色已解锁（locked=false）→ 无 paywall，详建可见', (tester) async {
      await _pump(
          tester,
          const TriageResult(
              status: TriageStatus.done,
              dangerLevel: DangerLevel.green,
              locked: false,
              advice: '继续观察精神与进食'));
      expect(find.byKey(const ValueKey('triageUnlockCta')), findsNothing);
      expect(find.text('继续观察精神与进食'), findsOneWidget);
    });
  });

  group('TriageUnlockController', () {
    test('免费额度成功 → unlocked', () async {
      final repo = _FakeRepo(
          unlockResult: const UnlockResult(
              unlocked: true,
              result: TriageResult(
                  status: TriageStatus.done, dangerLevel: DangerLevel.green, locked: false)));
      final c = ProviderContainer(
          overrides: [triageRepositoryProvider.overrideWithValue(repo)]);
      addTearDown(c.dispose);
      await c.read(triageUnlockControllerProvider.notifier).unlock(7, UnlockMethod.freeQuota);
      final st = c.read(triageUnlockControllerProvider);
      expect(st.phase, UnlockPhase.unlocked);
      expect(st.isUnlockedFor(7), isTrue);
      expect(st.isUnlockedFor(8), isFalse); // 不跨 triage 串态
    });

    test('PawCoin 成功 → unlocked', () async {
      final repo = _FakeRepo(
          unlockResult: const UnlockResult(
              unlocked: true,
              result: TriageResult(status: TriageStatus.done, locked: false)));
      final c = ProviderContainer(
          overrides: [triageRepositoryProvider.overrideWithValue(repo)]);
      addTearDown(c.dispose);
      await c.read(triageUnlockControllerProvider.notifier).unlock(7, UnlockMethod.pawcoin);
      expect(c.read(triageUnlockControllerProvider).phase, UnlockPhase.unlocked);
    });

    test('现金 → waitingPayment + payment', () async {
      final repo = _FakeRepo(
          unlockResult: const UnlockResult(
              unlocked: false,
              payment:
                  PaymentInfo(token: 'inttok', channel: 'QRIS', amount: 10000, status: 'PENDING')));
      final c = ProviderContainer(
          overrides: [triageRepositoryProvider.overrideWithValue(repo)]);
      addTearDown(c.dispose);
      await c.read(triageUnlockControllerProvider.notifier).unlock(7, UnlockMethod.qris);
      final st = c.read(triageUnlockControllerProvider);
      expect(st.phase, UnlockPhase.waitingPayment);
      expect(st.payment?.token, 'inttok');
    });

    test('免费 409 → quotaExhausted', () async {
      final repo = _FakeRepo(error: _dio409());
      final c = ProviderContainer(
          overrides: [triageRepositoryProvider.overrideWithValue(repo)]);
      addTearDown(c.dispose);
      await c.read(triageUnlockControllerProvider.notifier).unlock(7, UnlockMethod.freeQuota);
      final st = c.read(triageUnlockControllerProvider);
      expect(st.phase, UnlockPhase.error);
      expect(st.errorKind, UnlockErrorKind.quotaExhausted);
    });

    test('PawCoin 409 → insufficientBalance', () async {
      final repo = _FakeRepo(error: _dio409());
      final c = ProviderContainer(
          overrides: [triageRepositoryProvider.overrideWithValue(repo)]);
      addTearDown(c.dispose);
      await c.read(triageUnlockControllerProvider.notifier).unlock(7, UnlockMethod.pawcoin);
      expect(c.read(triageUnlockControllerProvider).errorKind, UnlockErrorKind.insufficientBalance);
    });
  });
}
