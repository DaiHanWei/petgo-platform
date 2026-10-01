import 'package:flutter/material.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:go_router/go_router.dart';

import '../../../core/theme/colors.dart';
import '../../../l10n/app_localizations.dart';
import '../../../shared/utils/date_format.dart';
import '../../../shared/widgets/empty_state.dart';
import '../../profile/presentation/pet_insights_page.dart';
import '../data/pet_passport_repository.dart';
import '../domain/passport_snapshot.dart';
import 'passport_layout.dart';
import 'passport_page_face.dart';
import 'share/passport_share_card.dart';

/// 已买护照版本列表「Versi yang dibeli」（V1.3.2 Story 3.4 · AC7.3）：每行「{日期} · {N} cap」→ 回看页。
/// 只列已付（服务端过滤）。重新导出在回看页（Story 4.3）。
class PassportVersionsPage extends ConsumerWidget {
  const PassportVersionsPage({super.key});

  @override
  Widget build(BuildContext context, WidgetRef ref) {
    final l10n = AppLocalizations.of(context);
    final async = ref.watch(passportSnapshotsProvider);
    return Scaffold(
      backgroundColor: AppColors.cream,
      appBar: AppBar(backgroundColor: AppColors.cream, title: Text(l10n.passportPurchasedVersions)),
      body: async.when(
        loading: () => const Center(child: CircularProgressIndicator()),
        error: (_, _) => EmptyState(
          title: l10n.passportLoadFailed,
          icon: Icons.cloud_off_rounded,
          actionLabel: l10n.placeRetry,
          onAction: () => ref.invalidate(passportSnapshotsProvider),
        ),
        data: (items) => ListView.separated(
          key: const ValueKey('passportVersionsList'),
          padding: const EdgeInsets.fromLTRB(16, 8, 16, 24),
          itemCount: items.length,
          separatorBuilder: (_, _) => const SizedBox(height: 10),
          itemBuilder: (context, i) => _Row(item: items[i]),
        ),
      ),
    );
  }
}

class _Row extends StatelessWidget {
  const _Row({required this.item});

  final PassportSnapshotItem item;

  @override
  Widget build(BuildContext context) {
    final l10n = AppLocalizations.of(context);
    return Material(
      color: AppColors.card,
      borderRadius: BorderRadius.circular(14),
      child: InkWell(
        key: ValueKey('passportVersion_${item.snapshotToken}'),
        borderRadius: BorderRadius.circular(14),
        onTap: () => context.push(PetInsightsRoutes.passportVersionFor(item.snapshotToken)),
        child: Container(
          constraints: const BoxConstraints(minHeight: 56),
          padding: const EdgeInsets.symmetric(horizontal: 16, vertical: 14),
          child: Row(
            children: [
              const Icon(Icons.menu_book_rounded, color: AppColors.mint),
              const SizedBox(width: 12),
              Expanded(
                child: Text(
                  l10n.passportPurchasedRow(formatDayMonthYear(context, item.paidAt.toLocal()), item.stampCount),
                  style: const TextStyle(fontSize: 14, fontWeight: FontWeight.w600, color: AppColors.ink),
                ),
              ),
              const Icon(Icons.chevron_right, color: AppColors.muted),
            ],
          ),
        ),
      ),
    );
  }
}

/// 已买版本回看（V1.3.2 Story 3.4 · AC7.3）：同护照内页版式、**无水印**、只读 ——
/// 只读快照接口，不调实时护照接口；不可翻到实时数据、不出购买按钮、章本体不可点。
///
/// V1.3.2 Story 4.3：吸底「Bagikan」→ 护照卡预览，卡面**按该快照**渲染、恒无水印（已付版本）。
class PassportVersionPage extends ConsumerStatefulWidget {
  const PassportVersionPage({super.key, required this.token});

  final String token;

  @override
  ConsumerState<PassportVersionPage> createState() => _PassportVersionPageState();
}

class _PassportVersionPageState extends ConsumerState<PassportVersionPage> {
  final PageController _pages = PageController();
  int _index = 0;

  @override
  void dispose() {
    _pages.dispose();
    super.dispose();
  }

  @override
  Widget build(BuildContext context) {
    final l10n = AppLocalizations.of(context);
    final async = ref.watch(passportSnapshotProvider(widget.token));
    final snapshot = async.value;
    return Scaffold(
      backgroundColor: AppColors.cream,
      appBar: AppBar(backgroundColor: AppColors.cream, title: Text(l10n.passportPageTitle)),
      bottomNavigationBar: snapshot == null || snapshot.stamps.isEmpty
          ? null
          : SafeArea(
              minimum: const EdgeInsets.fromLTRB(16, 8, 16, 12),
              child: FilledButton(
                key: const ValueKey('passportVersionShareCta'),
                style: FilledButton.styleFrom(
                    minimumSize: const Size.fromHeight(48),
                    backgroundColor: AppColors.mint,
                    foregroundColor: AppColors.onAccent),
                onPressed: () => openPassportSharePreview(context,
                    data: PassportShareData.fromSnapshot(snapshot), watermarked: false),
                child: Text(l10n.cardShareImage),
              ),
            ),
      body: async.when(
        loading: () => const Center(child: CircularProgressIndicator()),
        error: (_, _) => EmptyState(
          title: l10n.passportLoadFailed,
          icon: Icons.cloud_off_rounded,
          actionLabel: l10n.placeRetry,
          onAction: () => ref.invalidate(passportSnapshotProvider(widget.token)),
        ),
        data: (d) => PassportFrame(
          header: PassportHeader(petName: d.petName, passportNo: d.passportNo),
          footer: Text(l10n.passportPageFooter(_index + 1, d.stamps.length),
              key: const ValueKey('passportVersionFooter'),
              style: const TextStyle(fontSize: 13, color: AppColors.ink2, fontFeatures: [FontFeature.tabularFigures()])),
          child: PassportPageBlock(
            key: const ValueKey('passportVersionBlock'),
            child: PageView.builder(
              controller: _pages,
              itemCount: d.stamps.length,
              onPageChanged: (i) => setState(() => _index = i),
              itemBuilder: (context, i) => PassportPageFace(stamp: d.stamps[i], pageIndex: i),
            ),
          ),
        ),
      ),
    );
  }
}
