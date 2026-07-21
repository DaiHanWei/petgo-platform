import 'package:flutter/material.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:tailtopia/features/pawcoin/domain/pawcoin_transaction.dart';
import 'package:tailtopia/features/pawcoin/presentation/pawcoin_controller.dart';
import 'package:tailtopia/features/triage/data/triage_repository.dart';
import 'package:tailtopia/features/triage/presentation/widgets/unlock_method_sheet.dart';
import 'package:tailtopia/l10n/app_localizations.dart';

/// bug 20260721-322：AI 解锁弹层显示 PawCoin 余额 0，但用户实有 40k。
/// 根因：`showUnlockMethodSheet` 同步读 `pawCoinProvider.value?.balance ?? 0`，
/// 用户未进过钱包页时 AsyncNotifier 仍在 Loading → .value 为 null → 恒 0。
/// 期望：无论是否进过钱包页，弹层都拿到真实余额（await .future）。

/// 异步 build 的假 PawCoin 控制器：余额 40000，但**首帧同步读为 Loading**（复现根因）。
class _FakePawCoinController extends PawCoinController {
  @override
  Future<PawCoinState> build() async =>
      const PawCoinState(balance: 40000, items: <PawCoinTxnItem>[]);
}

/// 免费额度耗尽的假 repo（隔离到 PawCoin 方式）。
class _QuotaZeroRepo implements TriageRepository {
  @override
  Future<FreeQuotaView> fetchFreeQuota() async =>
      const FreeQuotaView(limit: 1, used: 1, remaining: 0);

  @override
  Future<UnlockResult> unlockTriage(int triageId, UnlockMethod method) async =>
      throw UnimplementedError();

  @override
  Future<int> submitTriage({
    String? symptomText,
    List<String> imageObjectKeys = const <String>[],
    int? petId,
    String? idempotencyKey,
  }) async =>
      throw UnimplementedError();

  @override
  Future<TriageResult> pollTriage(int triageId) async =>
      throw UnimplementedError();
}

void main() {
  testWidgets(
      'bug 322: 未进钱包页时解锁弹层也读到真实余额（40k≥价 → PawCoin 可选，不显 Top up）',
      (tester) async {
    await tester.pumpWidget(
      ProviderScope(
        overrides: [
          pawCoinProvider.overrideWith(_FakePawCoinController.new),
          triageRepositoryProvider.overrideWithValue(_QuotaZeroRepo()),
        ],
        child: MaterialApp(
          localizationsDelegates: AppLocalizations.localizationsDelegates,
          supportedLocales: AppLocalizations.supportedLocales,
          home: Consumer(
            builder: (context, ref, _) => Scaffold(
              body: Center(
                child: ElevatedButton(
                  onPressed: () =>
                      showUnlockMethodSheet(context, ref, priceIdr: 30000),
                  child: const Text('open'),
                ),
              ),
            ),
          ),
        ),
      ),
    );

    await tester.tap(find.text('open'));
    await tester.pumpAndSettle();

    // 弹层已开：PawCoin 行存在。
    expect(find.byKey(const ValueKey('unlockMethodPawcoin')), findsOneWidget);
    // 余额 40000 ≥ 价 30000 → PawCoin 足额可选，右侧「Top up first →」不应出现。
    // 修复前同步读余额=0 → 误判不足 → 显示 Top up first（测试失败）。
    expect(find.text('Top up first →'), findsNothing);
  });
}
