import 'package:dio/dio.dart';
import 'package:flutter/material.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:go_router/go_router.dart';

import '../../../core/network/problem_detail.dart';
import '../../../core/router/route_intent.dart';
import '../../../core/theme/colors.dart';
import '../../../l10n/app_localizations.dart';
import '../../../shared/widgets/app_toast.dart';
import '../../auth/domain/auth_guard.dart';
import '../../pet_passport/data/pet_passport_repository.dart';
import '../../profile/data/profile_repository.dart';
import '../data/location_service.dart';
import '../data/place_repository.dart';
import 'place_checkin_success_page.dart';
import 'place_comments_controller.dart';

/// 场所详情页的整宽「Check-in」按钮（V1.3.2 batch-a Story 1.1 · AC4）。
///
/// 流程：`requireLogin` → 读定位权限（不弹窗）→ `denied` 才 `request()`；`permanentlyDenied`（含本次
/// `request()` 后刚变成永久拒绝，即安卓「不再询问」）弹「去设置」对话框 → 取一次坐标（null = GPS 关 / 超时 → 轻提示）→ 以**原始精度**提交。
///
/// 三态：可点 / 提交中（loading、防重复点击）/ 今日已打卡（**禁用，不隐藏**）。
///
/// 🛡 坐标只进这一个请求体：不 log、不进埋点、不进 toast。
class PlaceCheckinButton extends ConsumerStatefulWidget {
  const PlaceCheckinButton({super.key, required this.token, required this.checkedInToday});

  /// 详情页的场所 token（打卡后按它刷新详情）。
  final String token;

  /// 服务端下发的「今日已打卡」（游客 null）。
  final bool? checkedInToday;

  @override
  ConsumerState<PlaceCheckinButton> createState() => _PlaceCheckinButtonState();
}

class _PlaceCheckinButtonState extends ConsumerState<PlaceCheckinButton> {
  bool _submitting = false;

  /// 本页刚打卡成功 / 刚收到 409 → 直接切禁用态，不等详情重拉回来。
  bool _doneLocally = false;

  bool get _done => _doneLocally || widget.checkedInToday == true;

  @override
  Widget build(BuildContext context) {
    final l10n = AppLocalizations.of(context);
    return SizedBox(
      width: double.infinity,
      child: FilledButton(
        key: const ValueKey('placeCheckinButton'),
        onPressed: (_done || _submitting) ? null : _onTap,
        style: FilledButton.styleFrom(
          minimumSize: const Size.fromHeight(46),
          backgroundColor: AppColors.mint,
          disabledBackgroundColor: AppColors.line2,
          disabledForegroundColor: AppColors.muted,
        ),
        child: _submitting
            ? const SizedBox.square(
                dimension: 18,
                child: CircularProgressIndicator(strokeWidth: 2, color: Colors.white),
              )
            : Text(_done ? l10n.placeCheckinDoneToday : l10n.placeCheckinButton),
      ),
    );
  }

  void _onTap() {
    requireLogin(
      ref,
      context,
      // 详情页是 push 进来的：用 onResume 的命令式回调，不用会换掉整个栈的声明式 location。
      pendingAction: RouteIntent(onResume: () {
        if (mounted) _start();
      }),
      onAllowed: _start,
    );
  }

  Future<void> _start() async {
    if (_submitting || _done) return;
    final l10n = AppLocalizations.of(context);
    final gateway = ref.read(locationGatewayProvider);
    // 打卡用新鲜定点（不接受列表排序那条的 10 分钟缓存，见 checkinCoordinatesProvider）。
    final locate = ref.read(checkinCoordinatesProvider);
    final repo = ref.read(placeRepositoryProvider);
    final container = ProviderScope.containerOf(context, listen: false);
    // 权限判定与引导弹窗**不进 loading 态**：弹窗开着时按钮转圈，关掉后又得复位，徒增一个中间态。
    var permission = await gateway.status();
    if (permission == LocationPermissionOutcome.permanentlyDenied) {
      if (mounted) await showLocationForCheckinDialog(context, gateway);
      return;
    }
    if (permission == LocationPermissionOutcome.denied) {
      permission = await gateway.request();
      // 本次拒绝时勾了「不再询问」→ 系统框以后再也不会弹，当场引导去设置（待确认 1.2，产品 2026-10-02 定）。
      if (permission == LocationPermissionOutcome.permanentlyDenied) {
        if (mounted) await showLocationForCheckinDialog(context, gateway);
        return;
      }
      // 仍拒绝 → 不打卡，按钮保持可点（AC4.2）。
      if (permission != LocationPermissionOutcome.granted) return;
    }
    if (!mounted || _submitting) return;
    setState(() => _submitting = true);
    try {
      final coords = await locate();
      if (coords == null) {
        if (mounted) showAppToast(context, l10n.placeCheckinLocationUnavailable);
        return;
      }
      final pet = await ref.read(petProfileProvider.future);
      if (pet == null) {
        if (mounted) showAppToast(context, l10n.placeCheckinNoPet);
        return;
      }
      // 🔴 原始精度提交（不经 placeDetailQueryFor 的三位小数归一）。
      final result = await repo.checkIn(
        widget.token,
        latitude: coords.latitude,
        longitude: coords.longitude,
        petIds: [pet.id],
      );
      _doneLocally = true;
      invalidatePlaceDetailIn(container, widget.token);
      // Story 1.2 复审：栈里可能已有一个护照页（空态「Cari Tempat」→ 列表 → 打卡），
      // 不失效的话「Lihat Paspor」拿到的是缓存的旧护照，看不到新章、也定位不到它。
      container.invalidate(petPassportProvider);
      if (!mounted) return;
      context.push(
        PlaceCheckinSuccessPage.routeFor(result.placeToken.isEmpty ? widget.token : result.placeToken),
        extra: PlaceCheckinSuccessArgs(result: result, petName: pet.name),
      );
    } on DioException catch (e) {
      _onFailure(l10n, e, container);
    } catch (_) {
      if (mounted) showAppToast(context, l10n.placeCheckinFailed);
    } finally {
      if (mounted) setState(() => _submitting = false);
    }
  }

  /// 失败按 ProblemDetail `typeSlug` 分流（AC4.4）。🔴 不展示 detail 原文。
  void _onFailure(AppLocalizations l10n, DioException e, ProviderContainer container) {
    final slug = ProblemDetail.fromDioException(e)?.typeSlug;
    if (slug == 'checkin-already-today') {
      _doneLocally = true;
      return;
    }
    if (e.response?.statusCode == 404 || slug == 'not-found') {
      // 既有「场所不存在」处理：刷新详情 → 页面落统一的 404 空态。
      invalidatePlaceDetailIn(container, widget.token);
      return;
    }
    if (!mounted) return;
    final text = switch (slug) {
      'checkin-too-far' => l10n.placeCheckinTooFar,
      'checkin-no-pet' => l10n.placeCheckinNoPet,
      _ => l10n.placeCheckinFailed,
    };
    showAppToast(context, text);
  }
}

/// 定位被永久拒绝时的引导对话框（AC4.2，样式照 `showMediaPermissionDeniedDialog`）。
///
/// 「Nanti」关闭；「Buka Pengaturan」走 [LocationGateway.openSettings]（复用 `mediaOpenSettings` 文案）。
Future<void> showLocationForCheckinDialog(BuildContext context, LocationGateway gateway) async {
  final l10n = AppLocalizations.of(context);
  await showDialog<void>(
    context: context,
    builder: (ctx) => AlertDialog(
      key: const ValueKey('placeCheckinLocationDialog'),
      title: Text(l10n.placeCheckinLocationTitle),
      content: Text(l10n.placeCheckinLocationBody),
      actions: [
        TextButton(
          onPressed: () => Navigator.of(ctx).pop(),
          child: Text(l10n.placeCheckinLocationLater),
        ),
        FilledButton(
          key: const ValueKey('placeCheckinOpenSettings'),
          onPressed: () {
            Navigator.of(ctx).pop();
            gateway.openSettings();
          },
          child: Text(l10n.mediaOpenSettings),
        ),
      ],
    ),
  );
}
