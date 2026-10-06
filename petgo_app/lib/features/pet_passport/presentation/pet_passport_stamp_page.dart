import 'package:flutter/material.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:go_router/go_router.dart';

import '../../../core/theme/colors.dart';
import '../../../l10n/app_localizations.dart';
import '../../../shared/utils/date_format.dart';
import '../../../shared/widgets/empty_state.dart';
import '../../place/presentation/place_detail_page.dart';
import '../data/pet_passport_repository.dart';
import '../domain/pet_passport.dart';
import 'passport_layout.dart';
import 'passport_page_face.dart';

/// B5 章详情 / B6 场所不可用变体（V1.3.2 batch-a Story 1.3 · AC3 / AC4）。**独立页，非抽屉**。
///
/// 数据直接取护照接口已加载的 [petPassportProvider]（不新增接口），按 [placeToken] 找章；
/// 找不到（数据变了）→「Tempat tidak ditemukan」空态，不崩。
///
/// B6（`available == false`）：章面、首次日期、次数、内页局部**照常**；只把地址行与「Lihat tempat」
/// 换成灰色信息块「Tempat tidak ditemukan / Tempat ini sudah tidak terdaftar」（无按钮）；**不做整页空态**。
class PetPassportStampPage extends ConsumerWidget {
  const PetPassportStampPage({super.key, required this.placeToken});

  final String placeToken;

  @override
  Widget build(BuildContext context, WidgetRef ref) {
    final l10n = AppLocalizations.of(context);
    final async = ref.watch(petPassportProvider);
    return Scaffold(
      backgroundColor: AppColors.cream,
      appBar: AppBar(
        backgroundColor: AppColors.cream,
        scrolledUnderElevation: 0,
        title: Text(l10n.passportPageTitle),
      ),
      body: switch (async) {
        AsyncData(:final value) => _body(context, l10n, value),
        AsyncError() => EmptyState(
            title: l10n.passportLoadFailed,
            icon: Icons.cloud_off_rounded,
            actionLabel: l10n.placeRetry,
            onAction: () => ref.invalidate(petPassportProvider),
          ),
        _ => const Center(child: CircularProgressIndicator()),
      },
    );
  }

  Widget _body(BuildContext context, AppLocalizations l10n, PetPassport p) {
    final index = p.stamps.indexWhere((s) => s.placeToken == placeToken);
    if (index < 0) {
      return EmptyState(
        key: const ValueKey('passportStampMissing'),
        title: l10n.placeUnavailableTitle,
        icon: Icons.place_outlined,
        actionLabel: MaterialLocalizations.of(context).backButtonTooltip,
        onAction: () => context.pop(),
      );
    }
    return ListView(
      padding: const EdgeInsets.fromLTRB(20, 4, 20, 32),
      children: [PassportStampDetail(passport: p, index: index)],
    );
  }
}

/// 章详情的内容块（V1.3.2 Story 1.3 · 2026-10-06 按产品要求改版）：上半与护照单章页同一张护照本，
/// 下方地点名 / 首次到访 / 到访次数，再下地址 +「See place」（场所不可用时为灰色说明块）。
///
/// B5 章详情页与打卡成功页（Story 1.1，[showSeePlace] = false：成功页本就从场所页进来）共用。
class PassportStampDetail extends StatelessWidget {
  const PassportStampDetail({
    super.key,
    required this.passport,
    required this.index,
    this.showSeePlace = true,
    this.showWatermark = false,
  });

  final PetPassport passport;
  final int index;
  final bool showSeePlace;

  /// 是否按「当前版本是否已买」叠水印。缺省不叠：单章页一律无水印，水印只在纵览（2026-10-06 产品）。
  final bool showWatermark;

  @override
  Widget build(BuildContext context) {
    final l10n = AppLocalizations.of(context);
    final p = passport;
    final s = p.stamps[index];
    final date = s.firstVisitDate == null ? '' : formatDayMonthYear(context, s.firstVisitDate!);
    return Column(
      crossAxisAlignment: CrossAxisAlignment.stretch,
      children: [
        // 2026-10-06 产品：上半部分与护照单章页同一张护照本（B2 的脸），页脚「Halaman i」；水印规则同护照页。
        PassportBook(
          key: const ValueKey('passportStampBook'),
          petName: p.petName,
          passportNo: p.passportNo,
          watermarked: showWatermark && !p.currentVersionUnlocked,
          footer: PassportBookFooter(label: l10n.passportPageLabel(index + 1), bold: false, showArrows: false),
          content: PassportPageFace(stamp: s, pageIndex: index),
        ),
        const SizedBox(height: 20),
        Text(s.placeName,
            textAlign: TextAlign.center,
            style: const TextStyle(fontSize: 20, fontWeight: FontWeight.w700, color: AppColors.ink)),
        const SizedBox(height: 8),
        Text(l10n.passportStampFirstVisit(date),
            textAlign: TextAlign.center, style: const TextStyle(fontSize: 13, color: AppColors.ink2)),
        const SizedBox(height: 2),
        Text(l10n.passportStampVisits(s.visitCount),
            textAlign: TextAlign.center, style: const TextStyle(fontSize: 13, color: AppColors.ink2)),
        const SizedBox(height: 20),
        if (s.available) ...[
          if (s.addressText != null)
            Row(
              key: const ValueKey('passportStampAddress'),
              crossAxisAlignment: CrossAxisAlignment.start,
              children: [
                const Icon(Icons.place_outlined, size: 18, color: AppColors.muted),
                const SizedBox(width: 6),
                Expanded(
                  child: Text(s.addressText!,
                      style: const TextStyle(fontSize: 13, height: 1.5, color: AppColors.ink2)),
                ),
              ],
            ),
          if (showSeePlace) ...[
          const SizedBox(height: 16),
          FilledButton(
            key: const ValueKey('passportSeePlace'),
            style: FilledButton.styleFrom(
                minimumSize: const Size.fromHeight(48), backgroundColor: AppColors.mint),
            onPressed: () => context.push(
                PlaceDetailPage.routeFor(s.placeToken, from: kPlaceDetailFromPassport)),
            child: Text(l10n.passportSeePlace),
          ),
          ],
        ] else
          Container(
            key: const ValueKey('passportStampUnavailable'),
            padding: const EdgeInsets.all(14),
            decoration: BoxDecoration(
              color: AppColors.line2,
              borderRadius: BorderRadius.circular(12),
            ),
            child: Column(
              children: [
                Text(l10n.placeUnavailableTitle,
                    textAlign: TextAlign.center,
                    style: const TextStyle(
                        fontSize: 14, fontWeight: FontWeight.w600, color: AppColors.ink2)),
                const SizedBox(height: 4),
                Text(l10n.placeUnavailableBody,
                    textAlign: TextAlign.center,
                    style: const TextStyle(fontSize: 12, color: AppColors.muted)),
              ],
            ),
          ),
            ],
    );
  }
}
