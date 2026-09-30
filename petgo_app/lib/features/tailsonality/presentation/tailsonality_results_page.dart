import 'package:flutter/material.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:go_router/go_router.dart';

import '../../../core/theme/colors.dart';
import '../../../l10n/app_localizations.dart';
import '../../../shared/widgets/app_toast.dart';
import '../../../shared/widgets/empty_state.dart';
import '../data/tailsonality_providers.dart';
import 'tailsonality_retake.dart';
import 'tailsonality_routes.dart';
import 'widgets/tailsonality_intro_sheet.dart';
import 'widgets/ts_result_row.dart';

/// Tailsonality 结果列表页（V1.3.2 Story 2.6）：一只宠物的全部测试结果，最新在上（接口顺序）。
///
/// 吸底「Tes Ulang」走与结果页 ⋯ 菜单**同一个**重测流程 [startTailsonalityRetake]。
/// 本 story 不含佩戴（Story 3.3 在行尾 `trailing` 插槽加）。
class TailsonalityResultsPage extends ConsumerStatefulWidget {
  const TailsonalityResultsPage({super.key});

  @override
  ConsumerState<TailsonalityResultsPage> createState() => _TailsonalityResultsPageState();
}

class _TailsonalityResultsPageState extends ConsumerState<TailsonalityResultsPage> {
  bool _opening = false;

  /// 空态「Mulai Tes」：档案加载中会等一会儿，期间再点忽略（否则叠两层抽屉）；取不到给提示。
  Future<void> _startFromEmpty() async {
    if (_opening) return;
    _opening = true;
    final pet = await readPetForTailsonality(ref);
    _opening = false;
    if (!mounted) return;
    if (pet == null) {
      showAppToast(context, AppLocalizations.of(context).growthLoadFailed);
      return;
    }
    await showTailsonalityIntroSheet(context, pet);
  }

  @override
  Widget build(BuildContext context) {
    final l10n = AppLocalizations.of(context);
    final async = ref.watch(tailsonalityResultsProvider);
    final items = async.asData?.value;
    return Scaffold(
      backgroundColor: AppColors.cream,
      appBar: AppBar(backgroundColor: AppColors.cream, title: Text(l10n.tailsonalityHistoryTitle)),
      body: async.when(
        loading: () => const Center(child: CircularProgressIndicator()),
        error: (_, _) => Center(
          child: Column(
            mainAxisSize: MainAxisSize.min,
            children: [
              Text(l10n.growthLoadFailed, style: const TextStyle(color: AppColors.muted)),
              const SizedBox(height: 8),
              TextButton(
                key: const ValueKey('tsResultsRetry'),
                onPressed: () => ref.invalidate(tailsonalityResultsProvider),
                child: Text(l10n.commonRetry),
              ),
            ],
          ),
        ),
        data: (list) => list.isEmpty
            ? EmptyState(
                key: const ValueKey('tsResultsEmpty'),
                title: l10n.tailsonalityHistoryEmpty,
                icon: Icons.psychology_alt_outlined,
                actionLabel: l10n.tailsonalityIntroStart,
                onAction: _startFromEmpty,
              )
            : ListView.separated(
                key: const ValueKey('tsResultsList'),
                padding: const EdgeInsets.fromLTRB(16, 8, 16, 24),
                itemCount: list.length,
                separatorBuilder: (_, _) => const SizedBox(height: 10),
                itemBuilder: (context, i) => TsResultRow(
                  key: ValueKey('tsResultRow_${list[i].token}'),
                  result: list[i],
                  onTap: () => context.push(TailsonalityRoutes.result(list[i].token), extra: list[i]),
                ),
              ),
      ),
      bottomNavigationBar: items == null || items.isEmpty
          ? null
          : SafeArea(
              top: false,
              child: Padding(
                padding: const EdgeInsets.fromLTRB(16, 8, 16, 12),
                child: FilledButton(
                  key: const ValueKey('tsResultsRetake'),
                  onPressed: () => startTailsonalityRetake(context, ref),
                  style: FilledButton.styleFrom(
                    backgroundColor: AppColors.mint,
                    foregroundColor: AppColors.onAccent,
                    minimumSize: const Size.fromHeight(48),
                    shape: RoundedRectangleBorder(borderRadius: BorderRadius.circular(14)),
                  ),
                  child: Text(l10n.tailsonalityMenuRetake,
                      style: const TextStyle(fontSize: 15, fontWeight: FontWeight.w700)),
                ),
              ),
            ),
    );
  }
}
