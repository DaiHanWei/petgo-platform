import 'package:dio/dio.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';

import '../data/triage_repository.dart';

/// AI 详建解锁价（Story 2.4）。前端常量 = 后端默认（Rp10.000）；9-2 后台改价落地后宜由后端下发（[OPEN]）。
const int kAiUnlockPriceIdr = 10000;

/// 解锁流阶段（Story 2.4）。
enum UnlockPhase { idle, submitting, waitingPayment, unlocked, error }

/// 解锁失败种类（映射后端 409，供 UI 出友好文案，不显 ProblemDetail 原文）。
enum UnlockErrorKind { quotaExhausted, insufficientBalance, generic }

/// 解锁 UI 态（Story 2.4）。[triageId] 标识本次解锁归属，结果页据此匹配（避免跨 triage 串态）。
class TriageUnlockState {
  const TriageUnlockState({
    this.phase = UnlockPhase.idle,
    this.triageId,
    this.result,
    this.payment,
    this.payload,
    this.errorKind,
  });

  final UnlockPhase phase;
  final int? triageId;

  /// 现金路径二维码串（EMVCo，本地生成二维码；进入 [UnlockPhase.waitingPayment] 后有值）。
  final String? payload;

  /// 同步解锁成功后的已解锁结果（详建下发）。结果页据此去 paywall。
  final TriageResult? result;

  /// 现金路径的支付信息（进入 [UnlockPhase.waitingPayment] 后）。
  final PaymentInfo? payment;

  final UnlockErrorKind? errorKind;

  /// 指定 triage 是否已解锁（结果页据此覆盖显示）。
  bool isUnlockedFor(int id) =>
      phase == UnlockPhase.unlocked && result != null && triageId == id;
}

/// 解锁编排（Story 2.4）。免费/PawCoin 同步即出 [UnlockPhase.unlocked]；现金进 [UnlockPhase.waitingPayment]
/// （真到账轮询由等待页驱动 `pollTriage`，L2）；409 → [UnlockPhase.error]。非 family（结果页一次一 triage，
/// state 带 triageId 区分），照既有 `TriageController` 范式。
class TriageUnlockController extends Notifier<TriageUnlockState> {
  @override
  TriageUnlockState build() => const TriageUnlockState();

  /// 发起解锁。免费/PawCoin 同步：成功置 unlocked。现金：置 waitingPayment。
  Future<void> unlock(int triageId, UnlockMethod method) async {
    state = TriageUnlockState(phase: UnlockPhase.submitting, triageId: triageId);
    try {
      final UnlockResult res =
          await ref.read(triageRepositoryProvider).unlockTriage(triageId, method);
      if (res.unlocked) {
        state = TriageUnlockState(
            phase: UnlockPhase.unlocked, triageId: triageId, result: res.result);
      } else {
        // 现金：待支付。真到账后由二维码面板轮询 pollTriage 至 locked==false → markUnlocked。
        state = TriageUnlockState(
            phase: UnlockPhase.waitingPayment, triageId: triageId,
            payment: res.payment, payload: res.payload);
      }
    } on DioException catch (e) {
      state = TriageUnlockState(
          phase: UnlockPhase.error, triageId: triageId, errorKind: _classify(e, method));
    } catch (_) {
      state = TriageUnlockState(
          phase: UnlockPhase.error, triageId: triageId, errorKind: UnlockErrorKind.generic);
    }
  }

  /// 现金到账后（等待页轮询到 `locked==false`）用最新结果落地已解锁态。
  void markUnlocked(int triageId, TriageResult result) {
    state = TriageUnlockState(phase: UnlockPhase.unlocked, triageId: triageId, result: result);
  }

  /// 回到锁定态（用户取消/超时）。
  void reset() => state = const TriageUnlockState();

  /// 后端额度/余额不足统一 409（ProblemDetail）；按尝试的方式区分文案，不依赖 detail 原文。
  static UnlockErrorKind _classify(DioException e, UnlockMethod method) {
    if (e.response?.statusCode == 409) {
      return switch (method) {
        UnlockMethod.freeQuota => UnlockErrorKind.quotaExhausted,
        UnlockMethod.pawcoin => UnlockErrorKind.insufficientBalance,
        _ => UnlockErrorKind.generic,
      };
    }
    return UnlockErrorKind.generic;
  }
}

final NotifierProvider<TriageUnlockController, TriageUnlockState>
    triageUnlockControllerProvider =
    NotifierProvider<TriageUnlockController, TriageUnlockState>(TriageUnlockController.new);
