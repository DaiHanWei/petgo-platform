import 'package:flutter/material.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';

import '../../../core/analytics/analytics.dart';
import '../../../core/theme/colors.dart';
import '../../../l10n/app_localizations.dart';
import '../../../shared/widgets/app_toast.dart';
import '../data/account_report_repository.dart';
import '../domain/account_action_entry.dart';
import '../domain/account_report_reason.dart';

/// 账号举报抽屉（V1.1.4 Story 2.2，FR-58 · UI 稿 B1–B7）。
///
/// 视觉规格<b>逐项照抄</b>内容举报 `features/content/presentation/report_sheet.dart`
/// （内边距 22/12/22/28 · 手柄 36×4 · 原因卡圆角 13/边框 1.5 · 按钮 radius 14…），
/// 但有三处**刻意不照抄**：
/// 1. **理由枚举另起一套** —— 那边是内容维度五类，账号维度只有「骚扰」「其他」对得上；
/// 2. **失败要给提示** —— 那边的 `catch (_)` 只把按钮解禁，用户看到的是「按了一下，按钮又能按了」；
/// 3. **提交中加转圈** —— 那边只是禁用按钮，没有任何「正在发」的迹象。
///
/// 返回 true = 用户提交成功并点了「关闭」（调用方据此收起迷你卡那一层）。
///
/// [entry]：**从哪个界面发起的**。Story 4.1 会在这里统一收口埋点；
/// 本 story 只负责把它正确传进来（黑名单页那个入口的点击量要单独看，见 `AccountActionEntry`）。
Future<bool> openAccountReport(
  BuildContext context,
  WidgetRef ref,
  int targetUserId, {
  required bool alreadyReported,
  required AccountActionEntry entry,
}) async {
  // ⚠️ 成功与否用回调记在外层，不靠 pop 的返回值（评审三轮 #8）：举报一旦服务端确认成功，
  // 无论用户点「关闭」还是点蒙层/下拉收起抽屉，都必须让调用方知道成功了——否则已建 REPORT
  // 隐藏关系却被当成取消，迷你卡不收、Feed 卡片不消失、「已举报」标签不点亮（Story 2.3/2.4）。
  bool submittedOk = false;
  await showModalBottomSheet<void>(
    context: context,
    backgroundColor: AppColors.surface,
    isScrollControlled: true, // 「其他」展开输入框后要能被键盘顶起
    builder: (_) => _AccountReportSheet(
      targetUserId: targetUserId,
      alreadyReported: alreadyReported,
      entry: entry,
      ref: ref,
      onSubmitted: () => submittedOk = true,
    ),
  );
  return submittedOk;
}

class _AccountReportSheet extends StatefulWidget {
  const _AccountReportSheet({
    required this.targetUserId,
    required this.alreadyReported,
    required this.entry,
    required this.ref,
    required this.onSubmitted,
  });

  final int targetUserId;
  final bool alreadyReported;

  /// 发起入口。**Story 4.1 在提交成功处读它上报**；本 story 只保证它传到了这里。
  final AccountActionEntry entry;

  final WidgetRef ref;

  /// 服务端确认举报成功时回调（评审三轮 #8）：成功态与抽屉关闭方式解耦。
  final VoidCallback onSubmitted;

  @override
  State<_AccountReportSheet> createState() => _AccountReportSheetState();
}

class _AccountReportSheetState extends State<_AccountReportSheet> {
  static const int _detailMaxLength = 200;

  AccountReportReason? _selected;
  final TextEditingController _detail = TextEditingController();
  bool _submitting = false;
  bool _done = false; // 提交成功 → 抽屉内原地切成功态（B5）

  @override
  void initState() {
    super.initState();
    // 字数计数要实时更新。
    _detail.addListener(() => setState(() {}));
  }

  @override
  void dispose() {
    _detail.dispose();
    super.dispose();
  }

  String _label(AppLocalizations l10n, AccountReportReason r) => switch (r) {
        AccountReportReason.spam => l10n.accountReportReasonSpam,
        AccountReportReason.impersonation => l10n.accountReportReasonImpersonation,
        AccountReportReason.harassment => l10n.accountReportReasonHarassment,
        AccountReportReason.violatingContent => l10n.accountReportReasonViolatingContent,
        AccountReportReason.other => l10n.accountReportReasonOther,
      };

  /// 能不能提交：选了类型；若选的是「其他」还得填了说明。
  bool get _canSubmit {
    if (_selected == null || _submitting) return false;
    return _selected != AccountReportReason.other || _detail.text.trim().isNotEmpty;
  }

  Future<void> _submit() async {
    if (!_canSubmit) return;
    setState(() => _submitting = true);
    try {
      await widget.ref.read(accountReportRepositoryProvider).report(
            widget.targetUserId,
            _selected!,
            detail: _selected == AccountReportReason.other ? _detail.text : null,
          );
      // ⚠️ 埋点在**提交成功之后**：没提交、提交失败都不上报。
      // ⚠️ 「其他」的补充说明是**用户自由文本，绝不进埋点属性**（Analytics 有兜底剥离，但不依赖兜底）。
      Analytics.capture('social_account_report_submitted', {
        'entry': widget.entry.wire,
        'reason': _selected!.wire, // 受控词表五值，非自由文本
      });
      // 举报会在服务端**同时**建立一条 REPORT 隐藏关系（Story 2.1 AC5），
      // 它同样要计入「隐藏关系」这条口径，只是来源不同 —— 否则主动拉黑与举报隐藏没法分开看。
      Analytics.capture('social_user_hide_submitted', {
        'origin': 'REPORT',
        'entry': AccountActionEntry.reportFlow.wire,
      });
      widget.onSubmitted(); // 成功即记账（评审三轮 #8）：此后无论怎么关都算成功
      if (mounted) setState(() => _done = true); // 原地切成功态，不弹 toast、不自动收起
    } catch (_) {
      if (!mounted) return;
      // ⚠️ 这里是本 story 明确不照抄既有实现的地方：既有 report_sheet 的 catch 只把
      // `_submitting` 复位，用户看到的就是「按了一下，按钮又能按了」，不知道发生了什么。
      // 抽屉保持打开、**已选类型与已填说明一个都不清**，用户可以直接再点提交。
      setState(() => _submitting = false);
      // toast 放**顶部**：抽屉正开着，默认的底部位置会压在提交/取消按钮上。
      showAppToast(context, AppLocalizations.of(context).accountReportFailed, top: true);
    }
  }

  Widget _handle() => Center(
        child: Container(
          width: 36,
          height: 4,
          margin: const EdgeInsets.only(bottom: 16),
          decoration:
              BoxDecoration(color: AppColors.line, borderRadius: BorderRadius.circular(9999)),
        ),
      );

  @override
  Widget build(BuildContext context) {
    final l10n = AppLocalizations.of(context);
    return SafeArea(
      child: Padding(
        // 键盘弹起时把抽屉顶上去（「其他」的输入框在下半屏）。
        padding: EdgeInsets.only(bottom: MediaQuery.of(context).viewInsets.bottom),
        child: Padding(
          padding: const EdgeInsets.fromLTRB(22, 12, 22, 28),
          child: _done ? _doneView(l10n) : _formView(l10n),
        ),
      ),
    );
  }

  /// 表单态（B1 未选 / B2 其他展开 / B3 已选 / B4 提交中 / B6 失败 / B7 重复举报）。
  ///
  /// ⚠️ 这**六个态共用这一个视图**，所以「提交后无法撤销」那一行只写一次就覆盖全部六态（AC4）。
  /// 谁要是日后把某个态拆成独立视图，记得那行也要跟过去。
  Widget _formView(AppLocalizations l10n) => SingleChildScrollView(
        child: Column(
          mainAxisSize: MainAxisSize.min,
          crossAxisAlignment: CrossAxisAlignment.stretch,
          children: [
            _handle(),
            Text(l10n.accountReportTitle,
                style: const TextStyle(
                    fontSize: 16, fontWeight: FontWeight.w700, color: AppColors.ink)),
            const SizedBox(height: 4),
            // B7：已举报过 → 副标题换成紫底说明块（告诉他再报一次是有意义的）。
            if (widget.alreadyReported)
              Container(
                key: const ValueKey('accountReportRepeatNotice'),
                margin: const EdgeInsets.only(top: 4),
                padding: const EdgeInsets.symmetric(horizontal: 12, vertical: 10),
                decoration: BoxDecoration(
                  color: AppColors.cream2,
                  borderRadius: BorderRadius.circular(10),
                ),
                child: Text(l10n.accountReportRepeatNotice,
                    style: const TextStyle(
                        fontSize: 12, height: 1.5, color: AppColors.textSecondary)),
              )
            else
              Text(l10n.reportSubtitle,
                  style: const TextStyle(fontSize: 12, color: AppColors.textSecondary)),
            const SizedBox(height: 16),
            // ⚠️ 重复举报时**不预填上次选的类型**：第一次报骚扰、第二次报仿冒，正是「问题在升级」的证据。
            for (final r in AccountReportReason.values) _reasonCard(l10n, r),
            if (_selected == AccountReportReason.other) _detailField(l10n),
            const SizedBox(height: 4),
            // AC4：六个表单态都要有这一行。只说「不可撤销」，**不说会隐藏他的内容** ——
            // 成功态刻意不提隐藏，这里提了等于绕开那条取舍。
            Padding(
              padding: const EdgeInsets.only(bottom: 10),
              child: Text(
                l10n.accountReportIrreversible,
                key: const ValueKey('accountReportIrreversible'),
                textAlign: TextAlign.center,
                style: const TextStyle(fontSize: 11, color: AppColors.textTertiary),
              ),
            ),
            SizedBox(
              width: double.infinity,
              child: FilledButton(
                key: const ValueKey('accountReportSubmit'),
                style: FilledButton.styleFrom(
                  backgroundColor: AppColors.mint,
                  foregroundColor: AppColors.onAccent,
                  padding: const EdgeInsets.symmetric(vertical: 14),
                  shape: RoundedRectangleBorder(borderRadius: BorderRadius.circular(14)),
                ),
                onPressed: _canSubmit ? _submit : null,
                child: _submitting
                    // B4：提交中给转圈（既有内容举报只是禁用按钮，没有任何「正在发」的迹象）。
                    ? const SizedBox(
                        width: 16,
                        height: 16,
                        child: CircularProgressIndicator(
                            strokeWidth: 2, color: AppColors.onAccent),
                      )
                    : Text(l10n.reportSubmit,
                        style: const TextStyle(fontSize: 14, fontWeight: FontWeight.w700)),
              ),
            ),
            const SizedBox(height: 8),
            SizedBox(
              width: double.infinity,
              child: OutlinedButton(
                key: const ValueKey('accountReportCancel'),
                // 提交中取消一并禁用（防重复提交的第一道防线；服务端还有 5 秒去重兜底）。
                onPressed: _submitting ? null : () => Navigator.of(context).pop(),
                style: OutlinedButton.styleFrom(
                  foregroundColor: AppColors.textSecondary,
                  side: const BorderSide(color: AppColors.line, width: 1.5),
                  padding: const EdgeInsets.symmetric(vertical: 13),
                  shape: RoundedRectangleBorder(borderRadius: BorderRadius.circular(14)),
                ),
                child: Text(l10n.commonCancel),
              ),
            ),
          ],
        ),
      );

  /// 紫边单选卡（选中 = 紫边 + 紫浅底 + 实心单选点）。⚠️ `AppColors.mint` 是品牌紫不是绿。
  Widget _reasonCard(AppLocalizations l10n, AccountReportReason r) {
    final selected = _selected == r;
    return Padding(
      padding: const EdgeInsets.only(bottom: 10),
      child: InkWell(
        key: ValueKey('accountReportReason_${r.name}'),
        onTap: _submitting ? null : () => setState(() => _selected = r),
        borderRadius: BorderRadius.circular(13),
        child: Container(
          padding: const EdgeInsets.symmetric(horizontal: 14, vertical: 12),
          decoration: BoxDecoration(
            color: selected ? AppColors.cream2 : AppColors.surface,
            borderRadius: BorderRadius.circular(13),
            border: Border.all(color: selected ? AppColors.mint : AppColors.line, width: 1.5),
          ),
          child: Row(
            children: [
              Icon(selected ? Icons.radio_button_checked : Icons.radio_button_unchecked,
                  size: 20, color: selected ? AppColors.mint : AppColors.muted),
              const SizedBox(width: 10),
              Expanded(
                child: Text(_label(l10n, r),
                    style: const TextStyle(fontSize: 13, color: AppColors.ink)),
              ),
            ],
          ),
        ),
      ),
    );
  }

  /// 「其他」才展开的必填说明框 + 右下角 0 / 200 计数（其余四类不展开 —— 多个空字段只会增加运营阅读成本）。
  Widget _detailField(AppLocalizations l10n) => Padding(
        padding: const EdgeInsets.only(bottom: 10),
        child: Column(
          crossAxisAlignment: CrossAxisAlignment.end,
          children: [
            TextField(
              key: const ValueKey('accountReportDetail'),
              controller: _detail,
              enabled: !_submitting,
              maxLines: 3,
              maxLength: _detailMaxLength,
              style: const TextStyle(fontSize: 13, color: AppColors.ink),
              decoration: InputDecoration(
                hintText: l10n.accountReportDetailHint,
                hintStyle: const TextStyle(fontSize: 13, color: AppColors.muted),
                counterText: '', // 自己画计数，位置与 UI 稿一致
                contentPadding: const EdgeInsets.symmetric(horizontal: 14, vertical: 12),
                enabledBorder: OutlineInputBorder(
                  borderRadius: BorderRadius.circular(13),
                  borderSide: const BorderSide(color: AppColors.line, width: 1.5),
                ),
                focusedBorder: OutlineInputBorder(
                  borderRadius: BorderRadius.circular(13),
                  borderSide: const BorderSide(color: AppColors.mint, width: 1.5),
                ),
                disabledBorder: OutlineInputBorder(
                  borderRadius: BorderRadius.circular(13),
                  borderSide: const BorderSide(color: AppColors.line, width: 1.5),
                ),
              ),
            ),
            const SizedBox(height: 4),
            Text('${_detail.text.characters.length} / $_detailMaxLength',
                key: const ValueKey('accountReportDetailCounter'),
                style: const TextStyle(fontSize: 11, color: AppColors.textTertiary)),
          ],
        ),
      );

  /// 成功态（B5）：抽屉内原地切换，**不用 Toast、不自动收起**。
  ///
  /// ⚠️ 刻意不提「他的内容将被隐藏」：举报**帖子**会告知（既有 `reportHiddenToast`），
  /// 举报**账号**不告知。这是产品复核过的实锤差异，不是漏了。
  Widget _doneView(AppLocalizations l10n) => Column(
        mainAxisSize: MainAxisSize.min,
        children: [
          _handle(),
          const SizedBox(height: 8),
          const Text('✅', style: TextStyle(fontSize: 40)),
          const SizedBox(height: 10),
          Text(l10n.reportDoneTitle,
              style: const TextStyle(
                  fontSize: 15, fontWeight: FontWeight.w700, color: AppColors.ink)),
          const SizedBox(height: 6),
          Text(l10n.reportDoneBody,
              textAlign: TextAlign.center,
              style: const TextStyle(fontSize: 13, color: AppColors.textSecondary)),
          const SizedBox(height: 20),
          SizedBox(
            width: double.infinity,
            child: FilledButton(
              key: const ValueKey('accountReportDoneClose'),
              style: FilledButton.styleFrom(
                backgroundColor: AppColors.muted.withValues(alpha: 0.15),
                foregroundColor: AppColors.textSecondary,
                elevation: 0,
                padding: const EdgeInsets.symmetric(vertical: 13),
                shape: RoundedRectangleBorder(borderRadius: BorderRadius.circular(14)),
              ),
              // 成功与否已由 onSubmitted 记账（评审三轮 #8），这里只负责收起抽屉。
              onPressed: () => Navigator.of(context).pop(),
              child: Text(l10n.commonClose),
            ),
          ),
        ],
      );
}
