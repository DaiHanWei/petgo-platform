import 'package:dio/dio.dart';
import 'package:flutter/material.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:go_router/go_router.dart';

import '../../../core/network/problem_detail.dart';
import '../../../core/rive/rive_runtime.dart';
import '../../../core/router/route_intent.dart';
import '../../../core/theme/colors.dart';
import '../../../l10n/app_localizations.dart';
import '../../../shared/widgets/app_toast.dart';
import '../../auth/domain/auth_guard.dart';
import '../../pet_passport/data/pet_passport_repository.dart';
import '../../profile/data/milestone_repository.dart';
import '../../profile/data/pet_list_repository.dart';
import '../../profile/presentation/widgets/pet_picker_sheet.dart';
import '../data/location_service.dart';
import '../data/place_repository.dart';
import 'place_checkin_success_page.dart';
import 'place_comments_controller.dart';
import 'place_stamp_ceremony_page.dart';
import 'place_location_controller.dart';

/// 场所详情页的整宽「Check-in」按钮（V1.3.2 batch-a Story 1.1 · AC4）。
///
/// 流程：`requireLogin` → 读定位权限（不弹窗）→ `denied` 才 `request()`；`permanentlyDenied`（含本次
/// `request()` 后刚变成永久拒绝，即安卓「不再询问」）弹「去设置」对话框 → 取一次坐标（null = GPS 关 / 超时 → 轻提示）→ 以**原始精度**提交。
///
/// 三态：可点 / 提交中（loading、防重复点击）/ 今日已打卡（**禁用，不隐藏**）。
///
/// 🛡 坐标只进这一个请求体：不 log、不进埋点、不进 toast。
class PlaceCheckinButton extends ConsumerStatefulWidget {
  const PlaceCheckinButton({
    super.key,
    required this.token,
    required this.checkedInToday,
    this.checkedInPetIds,
  });

  /// 详情页的场所 token（打卡后按它刷新详情）。
  final String token;

  /// 服务端下发的「今日已打卡」（游客 null）。V1.4.0 multi-pet Story 1.3 起 = 全部正常宠物都打过才 true。
  final bool? checkedInToday;

  /// 今天已在此打过卡的本人宠物 id（V1.4.0 multi-pet Story 1.3 / 3.3）：选宠物抽屉据此置灰。
  /// null = 服务端没下发 → 抽屉不置灰任何行，靠提交时 409 兜底。
  final List<int>? checkedInPetIds;

  @override
  ConsumerState<PlaceCheckinButton> createState() => _PlaceCheckinButtonState();
}

class _PlaceCheckinButtonState extends ConsumerState<PlaceCheckinButton> {
  bool _submitting = false;

  /// 本次流程看到的正常宠物数（409 分流用：1 只 = 改造前口径）。
  int _activeCount = 1;

  /// 选宠物抽屉开着（仍算流程中、防重复点击）：按钮不转圈 —— 抽屉关掉 = 取消，转圈只会多一个中间态。
  bool _choosing = false;

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
        child: _submitting && !_choosing
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
    // 去设置走页面定位态的控制器：它会在回到 App 时重读权限，详情页的距离行随之出现（L2 验收 2026-10-05）。
    Future<void> openSettings() => container.read(placeLocationProvider.notifier).openSettings();
    // 权限判定与引导弹窗**不进 loading 态**：弹窗开着时按钮转圈，关掉后又得复位，徒增一个中间态。
    var permission = await gateway.status();
    if (permission == LocationPermissionOutcome.permanentlyDenied) {
      if (mounted) await showLocationForCheckinDialog(context, openSettings: openSettings);
      return;
    }
    // 本次经系统框刚授权：页面定位态还是「未授权」（只在进页时读一次），距离行不会出现。
    // 打卡流程结束后再失效它 —— 流程中失效会让详情页重建、本按钮被卸载，成功页就推不出去了。
    var grantedNow = false;
    if (permission == LocationPermissionOutcome.denied) {
      permission = await gateway.request();
      // 本次拒绝时勾了「不再询问」→ 系统框以后再也不会弹，当场引导去设置（待确认 1.2，产品 2026-10-02 定）。
      if (permission == LocationPermissionOutcome.permanentlyDenied) {
        if (mounted) await showLocationForCheckinDialog(context, openSettings: openSettings);
        return;
      }
      // 仍拒绝 → 不打卡，按钮保持可点（AC4.2）。
      if (permission != LocationPermissionOutcome.granted) return;
      grantedNow = true;
    }
    if (!mounted || _submitting) return;
    setState(() => _submitting = true);
    try {
      final coords = await locate();
      if (coords == null) {
        if (mounted) showAppToast(context, l10n.placeCheckinLocationUnavailable);
        return;
      }
      // V1.4.0 multi-pet Story 3.3 · AC5.1：只列正常（ACTIVE）宠物，只读的不出现（不是置灰）。
      final all = await ref.read(petListProvider.future);
      final active = [for (final p in all) if (!p.isReadOnly) p];
      if (active.isEmpty) {
        if (mounted) showAppToast(context, l10n.placeCheckinNoPet);
        return;
      }
      _activeCount = active.length;
      final already = {...?widget.checkedInPetIds};
      final List<PetListItem> chosen;
      if (active.length == 1) {
        // G4：只有一只正常宠物 → 不弹选择器，直接提交它（改造前流程）。
        chosen = active;
      } else {
        // G1 / G2：选宠物抽屉；关闭 = 取消本次打卡（按钮复位，不 Toast）。
        if (!mounted) return;
        setState(() => _choosing = true);
        final List<int>? picked;
        try {
          picked = await showPetPickerSheet(
            context,
            pets: active,
            title: l10n.petPickerCheckinTitle,
            body: l10n.petPickerCheckinBody,
            disabledPetIds: already,
            disabledSubtitle: l10n.petPickerCheckedInHere,
            submitLabel: l10n.petPickerCheckinSubmit,
          );
        } finally {
          if (mounted) setState(() => _choosing = false);
        }
        if (picked == null || picked.isEmpty || !mounted) return;
        chosen = [for (final p in active) if (picked.contains(p.id)) p];
      }
      // 🔴 原始精度提交（不经 placeDetailQueryFor 的三位小数归一）。
      final result = await repo.checkIn(
        widget.token,
        latitude: coords.latitude,
        longitude: coords.longitude,
        petIds: [for (final p in chosen) p.id],
      );
      // 全部正常宠物今天都打过了才切「已打卡」禁用态（与后端 checkedInToday 同口径）；否则等详情重拉。
      _doneLocally = active.every((p) => already.contains(p.id) || chosen.any((c) => c.id == p.id));
      invalidatePlaceDetailIn(container, widget.token);
      // Story 1.2 复审：栈里可能已有一个护照页（空态「Cari Tempat」→ 列表 → 打卡），
      // 不失效的话「Lihat Paspor」拿到的是缓存的旧护照，看不到新章、也定位不到它。
      container.invalidate(petPassportProvider);
      // V1.4.0 multi-pet Story 3.3 · AC5.5：每只所选宠物的里程碑（petId family）都失效。
      for (final p in chosen) {
        container.invalidate(milestoneListProvider(p.id));
      }
      // 逐宠物的章（1.3）；老后端没下发 → 每只都按顶层字段（降级，「待确认」）。
      final stamps = {for (final s in result.pets) s.petId: s};
      final petArgs = [
        for (final p in chosen)
          PlaceCheckinPetArgs(
            petId: p.id,
            name: p.name,
            avatarUrl: p.avatarUrl,
            isNewStamp: stamps[p.id]?.isNewStamp ?? result.isNewStamp,
            visitCount: stamps[p.id]?.visitCount ?? result.visitCount,
          ),
      ];
      final args = PlaceCheckinSuccessArgs(result: result, petName: chosen.first.name, pets: petArgs);
      // 新章才需要 Rive（AC6.4：任一所选宠物是新章就播一次，章面是场所章、各宠物相同）：后台初始化通常早已完成；
      // 没完成最多再等 500ms，等不到就不播（直接成功页）。
      final playCeremony = args.anyNewStamp && await RiveRuntime.ensureReady();
      if (!mounted) return;
      final placeToken = result.placeToken.isEmpty ? widget.token : result.placeToken;
      // V1.3.4 签到盖章动画：新章先进全屏动画页（结束后它 pushReplacement 成功页，返回仍回详情）；
      // 再访、或 Rive 原生库不可用时跳过动画页，直接成功页（ceremonyPlayed 为 false，保留小落章）。
      context.push(
        playCeremony
            ? PlaceStampCeremonyPage.routeFor(placeToken)
            : PlaceCheckinSuccessPage.routeFor(placeToken),
        extra: args,
      );
    } on DioException catch (e) {
      _onFailure(l10n, e, container);
    } catch (_) {
      if (mounted) showAppToast(context, l10n.placeCheckinFailed);
    } finally {
      if (mounted) setState(() => _submitting = false);
      if (grantedNow) container.invalidate(placeLocationProvider);
    }
  }

  /// 失败按 ProblemDetail `typeSlug` 分流（AC4.4）。🔴 不展示 detail 原文。
  void _onFailure(AppLocalizations l10n, DioException e, ProviderContainer container) {
    final slug = ProblemDetail.fromDioException(e)?.typeSlug;
    if (slug == 'checkin-already-today') {
      if (_activeCount <= 1) {
        // 单只正常宠物：与改造前一致 —— 直接切「已打卡」禁用态。
        _doneLocally = true;
        return;
      }
      // V1.4.0 multi-pet Story 3.3 · AC5.4：多宠（并发 / 状态过期）→ 刷新详情（逐宠物今日状态）+ 现有失败文案，
      // 不进成功页；按钮保持可点，抽屉按新状态置灰。
      invalidatePlaceDetailIn(container, widget.token);
      if (mounted) showAppToast(context, l10n.placeCheckinFailed);
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
/// 「Nanti」关闭；「Buka Pengaturan」走 [openSettings]（复用 `mediaOpenSettings` 文案）——
/// 调用方传页面定位态控制器的 `openSettings`，回到 App 时会重读权限。
Future<void> showLocationForCheckinDialog(BuildContext context,
    {required Future<void> Function() openSettings}) async {
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
            openSettings();
          },
          child: Text(l10n.mediaOpenSettings),
        ),
      ],
    ),
  );
}
