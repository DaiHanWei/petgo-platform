import 'package:flutter/material.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:go_router/go_router.dart';

import '../../../core/theme/colors.dart';
import '../../../core/theme/spacing.dart';
import '../../../core/theme/typography.dart';
import '../../../l10n/app_localizations.dart';
import '../data/consult_repository.dart';
import '../domain/consult_session.dart';
import 'consult_refresh.dart';
import 'consult_rating_dialog.dart';

/// 兽医咨询入口（Story 5.3 F1）。在线/离线两态 + 已有进行中跳转 + 离线软引导（不强制）。
///
/// 概率性在线展示「工作日 8:00–23:00 通常有兽医在线」（静态 l10n，**不显示人数**）；
/// 离线态「当前暂无兽医在线」+ 恢复时段 + 「先用 AI 分诊？」可点跳 FR-4A（不强制）。
///
/// Story 5.6 AC5（F12 · R2 补评分推迟）：进页若**有进行中会话**（[_active] 非空，即从「进行中」可恢复），
/// **推迟补弹评分**——不在用户正处理活跃会话时打断；仅当无活跃会话时才补弹一次（后端 `pendingRating`
/// 同样以「有活跃会话则空」双重兜底）。
class ConsultEntryPage extends ConsumerStatefulWidget {
  const ConsultEntryPage({super.key});

  @override
  ConsumerState<ConsultEntryPage> createState() => _ConsultEntryPageState();
}

class _ConsultEntryPageState extends ConsumerState<ConsultEntryPage> {
  bool _starting = false;
  ConsultSession? _active;
  bool _activeChecked = false;
  bool _ratingPromptHandled = false;

  /// 点「Mulai Konsultasi」时即时查后台在线态：查到无兽医在线 → 置 true 切到离线引导。
  /// 不再进页预查 / 轮询（决策：在线态只在用户真正发起时校验，避免缓存/轮询的时序问题）。
  bool _noVetOnline = false;

  @override
  void initState() {
    super.initState();
    _checkActive();
  }

  Future<void> _checkActive() async {
    try {
      final a = await ref.read(consultRepositoryProvider).active();
      if (!mounted) return;
      setState(() {
        _active = a;
        _activeChecked = true;
      });
      // AC5（F12·R2 补评分推迟）：有进行中会话 → 推迟补弹；仅无活跃会话时补弹一次。
      if (a == null) {
        await _maybePromptRating();
      }
    } catch (_) {
      if (mounted) setState(() => _activeChecked = true);
    }
  }

  /// 补弹评分（AC3 · 推迟门控后）：取待补弹的已关闭会话 → 弹一次 → 置 PROMPTED（无论是否评分，不再弹）。
  Future<void> _maybePromptRating() async {
    if (_ratingPromptHandled) return;
    _ratingPromptHandled = true;
    final repo = ref.read(consultRepositoryProvider);
    try {
      final pending = await repo.pendingRating();
      if (pending == null || !mounted) return;
      // 已评分会话只清补弹标记、不再弹评分：补弹只清标记不改 UNRATED，重复弹会触发后端 409。
      if (pending.rated) {
        await repo.markRatingPrompted(pending.id);
        return;
      }
      final result = await ConsultRatingDialog.show(context);
      await repo.markRatingPrompted(pending.id); // 弹后即不再弹（AC3）
      if (result != null) {
        await repo.rate(pending.id, result.stars, result.comment);
        ref.read(consultRefreshProvider.notifier).bump(); // 通知历史列表刷新已评分
      }
    } catch (_) {
      // 补弹失败静默，不阻断入口。
    }
  }

  Future<void> _start() async {
    if (_starting) return;
    // 点击即时查后台在线态（loading 在按钮上）。有兽医 → 去病例填写页（Story F：提交才发起 DIRECT
    // 会话 → 等待页）；无兽医 → 切到离线引导（AI 分诊 + 营业时间），不进发起流程。
    setState(() => _starting = true);
    try {
      final online = await ref.read(consultRepositoryProvider).vetOnline();
      if (!mounted) return;
      if (!online) {
        setState(() => _noVetOnline = true);
        return;
      }
      await context.push('/consult/case');
    } finally {
      if (mounted) setState(() => _starting = false);
    }
  }

  @override
  Widget build(BuildContext context) {
    final l10n = AppLocalizations.of(context);
    return Scaffold(
      backgroundColor: AppColors.base,
      body: SafeArea(
        child: Column(
          crossAxisAlignment: CrossAxisAlignment.stretch,
          children: [
            _backHeader(l10n),
            Expanded(
              child: Padding(
                padding: const EdgeInsets.fromLTRB(AppSpacing.xl, AppSpacing.sm, AppSpacing.xl, AppSpacing.xl),
                child: _body(l10n),
              ),
            ),
          ],
        ),
      ),
    );
  }

  Widget _body(AppLocalizations l10n) {
    // 已有进行中咨询 → 「查看进行中 →」（优先于发起）。
    if (_active != null) return _ongoing(l10n);
    // 仅等 active 检查（不再进页预查在线态）。
    if (!_activeChecked) return const Center(child: CircularProgressIndicator());
    // 点 Start 查到无兽医 → 离线引导；否则就绪态（步骤 + 发起按钮）。
    if (_noVetOnline) return _offline(l10n);
    return _ready(l10n);
  }

  /// 自定义返回 header（原型 konsultasi-home.html）：圆角灰底返回钮 + 17px 粗标题。
  Widget _backHeader(AppLocalizations l10n) {
    return Padding(
      padding: const EdgeInsets.fromLTRB(16, 8, 16, 4),
      child: Row(
        children: [
          InkWell(
            key: const ValueKey('consultEntryBack'),
            onTap: () => context.canPop() ? context.pop() : context.go('/home'),
            borderRadius: BorderRadius.circular(11),
            child: Container(
              width: 36,
              height: 36,
              alignment: Alignment.center,
              decoration: BoxDecoration(
                color: const Color(0xFFEFEDF3),
                borderRadius: BorderRadius.circular(11),
              ),
              child: const Icon(Icons.arrow_back, size: 18, color: AppColors.ink2),
            ),
          ),
          const SizedBox(width: 12),
          Text(l10n.consultEntryTitle,
              style: const TextStyle(fontSize: 17, fontWeight: FontWeight.w700, color: AppColors.ink)),
        ],
      ),
    );
  }

  Widget _ongoing(AppLocalizations l10n) {
    // 仅 WAITING 进等待/匹配页；IN_PROGRESS / PENDING_CLOSE 直达会话页。
    // 否则 waiting 页 _tick 不处理后两态 → 永停「匹配中」并在 1 分钟后误弹超时。
    final target = _active!.isWaiting
        ? '/consult/waiting/${_active!.id}'
        : '/consult/conversation/${_active!.id}';
    return Center(
      child: FilledButton(
        key: const ValueKey('consultViewActive'),
        onPressed: () => context.push(target),
        child: Text(l10n.consultViewActive),
      ),
    );
  }

  /// 就绪态（无在线提示条）：进页即展示流程 + 发起按钮；在线与否在点「Mulai」时即时校验。
  Widget _ready(AppLocalizations l10n) {
    return ListView(
      children: [
        // 3 步流程（原型 CARA KERJA）。
        Text(l10n.consultHowItWorks.toUpperCase(),
            style: const TextStyle(
                fontSize: 12,
                fontWeight: FontWeight.w700,
                letterSpacing: 0.4,
                color: AppColors.ink)),
        const SizedBox(height: 14),
        _StepRow(n: '1', color: AppColors.mint, title: l10n.consultStep1Title, desc: l10n.consultStep1Desc),
        const SizedBox(height: 12),
        _StepRow(n: '2', color: AppColors.mint500, title: l10n.consultStep2Title, desc: l10n.consultStep2Desc),
        const SizedBox(height: 12),
        _StepRow(n: '3', color: AppColors.violetSoft, title: l10n.consultStep3Title, desc: l10n.consultStep3Desc),
        const SizedBox(height: 22),
        // 责任声明。
        Text(l10n.consultEntryDisclaimer,
            textAlign: TextAlign.center,
            style: const TextStyle(fontSize: 11, height: 1.55, color: AppColors.muted)),
        const SizedBox(height: 18),
        // CTA：全宽紫底「Mulai Konsultasi →」。
        SizedBox(
          width: double.infinity,
          child: FilledButton(
            key: const ValueKey('consultStartButton'),
            onPressed: _starting ? null : _start,
            style: FilledButton.styleFrom(
              backgroundColor: AppColors.accentConsult,
              foregroundColor: AppColors.onAccent,
              padding: const EdgeInsets.symmetric(vertical: 15),
              shape: RoundedRectangleBorder(borderRadius: BorderRadius.circular(14)),
            ),
            child: _starting
                ? const SizedBox(
                    width: 20,
                    height: 20,
                    child: CircularProgressIndicator(
                        strokeWidth: 2.4, color: AppColors.onAccent),
                  )
                : Row(
                    mainAxisAlignment: MainAxisAlignment.center,
                    mainAxisSize: MainAxisSize.min,
                    children: [
                      Text(l10n.consultStart, style: AppTypography.button),
                      const SizedBox(width: 6),
                      const Icon(Icons.arrow_forward_rounded, size: 18),
                    ],
                  ),
          ),
        ),
      ],
    );
  }

  // 离线态（konsultasi-offline.html 1:1）：灰点状态条 + 紫渐变 AI 引导卡 + 营业时间卡。
  Widget _offline(AppLocalizations l10n) {
    return SingleChildScrollView(
      key: const ValueKey('consultOfflineState'),
      child: Column(
        crossAxisAlignment: CrossAxisAlignment.stretch,
        children: [
          // ① 离线状态条（灰点 + 标题 + 预计恢复时间）。
          Container(
            padding: const EdgeInsets.symmetric(horizontal: 16, vertical: 12),
            decoration: BoxDecoration(
              color: AppColors.line2,
              borderRadius: BorderRadius.circular(14),
            ),
            child: Row(
              children: [
                Container(
                  width: 10,
                  height: 10,
                  decoration: const BoxDecoration(shape: BoxShape.circle, color: AppColors.muted),
                ),
                const SizedBox(width: 10),
                Expanded(
                  child: Column(
                    crossAxisAlignment: CrossAxisAlignment.start,
                    children: [
                      Text(l10n.consultNoVetOnline,
                          style: const TextStyle(
                              fontSize: 13, fontWeight: FontWeight.w700, color: AppColors.ink)),
                      Text(l10n.consultOfflineWindow,
                          style: const TextStyle(fontSize: 11, color: AppColors.ink2)),
                    ],
                  ),
                ),
              ],
            ),
          ),
          const SizedBox(height: 18),
          // ② 紫渐变 AI 引导卡。
          Container(
            padding: const EdgeInsets.all(20),
            decoration: BoxDecoration(
              borderRadius: BorderRadius.circular(18),
              gradient: const LinearGradient(
                begin: Alignment.topLeft,
                end: Alignment.bottomRight,
                colors: [AppColors.mint, AppColors.mint500],
              ),
            ),
            child: Column(
              children: [
                const Text('⚡', style: TextStyle(fontSize: 34)),
                const SizedBox(height: 10),
                Text(l10n.consultOfflineAiPrompt,
                    textAlign: TextAlign.center,
                    style: const TextStyle(
                        fontSize: 15, fontWeight: FontWeight.w700, color: Colors.white)),
                const SizedBox(height: 6),
                Text(l10n.consultOfflineAiBody,
                    textAlign: TextAlign.center,
                    style: TextStyle(
                        fontSize: 12, height: 1.55, color: Colors.white.withValues(alpha: 0.85))),
                const SizedBox(height: 16),
                SizedBox(
                  width: double.infinity,
                  child: FilledButton(
                    key: const ValueKey('consultOfflineUseAi'),
                    onPressed: () => context.push('/triage/upload'),
                    style: FilledButton.styleFrom(
                      backgroundColor: Colors.white,
                      foregroundColor: AppColors.mint,
                      padding: const EdgeInsets.symmetric(vertical: 13),
                      shape: RoundedRectangleBorder(borderRadius: BorderRadius.circular(13)),
                      elevation: 0,
                    ),
                    child: Text('${l10n.consultOfflineUseAi} →',
                        style: const TextStyle(fontSize: 14, fontWeight: FontWeight.w700)),
                  ),
                ),
              ],
            ),
          ),
          const SizedBox(height: 20),
          // ③ 营业时间卡（JAM OPERASIONAL DOKTER）。
          Text(l10n.consultHoursTitle,
              style: const TextStyle(
                  fontSize: 12,
                  fontWeight: FontWeight.w700,
                  letterSpacing: 0.4,
                  color: AppColors.ink2)),
          const SizedBox(height: 14),
          Container(
            padding: const EdgeInsets.symmetric(horizontal: 16, vertical: 6),
            decoration: BoxDecoration(
              color: AppColors.surface,
              borderRadius: BorderRadius.circular(14),
              boxShadow: [
                BoxShadow(
                    color: Colors.black.withValues(alpha: 0.06),
                    blurRadius: 10,
                    offset: const Offset(0, 2)),
              ],
            ),
            child: Column(
              children: [
                _hourRow(l10n.consultHoursWeekday, '08:00 – 23:00', divider: true),
                _hourRow(l10n.consultHoursWeekend, '09:00 – 21:00', divider: false),
              ],
            ),
          ),
        ],
      ),
    );
  }

  Widget _hourRow(String day, String time, {required bool divider}) {
    return Container(
      padding: const EdgeInsets.symmetric(vertical: 8),
      decoration: divider
          ? const BoxDecoration(
              border: Border(bottom: BorderSide(color: AppColors.line2)))
          : null,
      child: Row(
        mainAxisAlignment: MainAxisAlignment.spaceBetween,
        children: [
          Text(day, style: const TextStyle(fontSize: 13, color: AppColors.ink)),
          Text(time,
              style: const TextStyle(
                  fontSize: 13, fontWeight: FontWeight.w600, color: AppColors.ink)),
        ],
      ),
    );
  }
}

/// 流程步骤行（原型）：编号紫渐变圆 + 标题 + 副标题。
class _StepRow extends StatelessWidget {
  const _StepRow(
      {required this.n, required this.color, required this.title, required this.desc});

  final String n;
  final Color color;
  final String title;
  final String desc;

  @override
  Widget build(BuildContext context) {
    return Row(
      crossAxisAlignment: CrossAxisAlignment.start,
      children: [
        Container(
          width: 32,
          height: 32,
          alignment: Alignment.center,
          decoration: BoxDecoration(color: color, shape: BoxShape.circle),
          child: Text(n,
              style: const TextStyle(
                  fontSize: 13, fontWeight: FontWeight.w700, color: AppColors.onAccent)),
        ),
        const SizedBox(width: 13),
        Expanded(
          child: Column(
            crossAxisAlignment: CrossAxisAlignment.start,
            children: [
              const SizedBox(height: 3),
              Text(title,
                  style: const TextStyle(
                      fontSize: 13, fontWeight: FontWeight.w700, color: AppColors.ink)),
              const SizedBox(height: 2),
              Text(desc,
                  style: const TextStyle(fontSize: 12, height: 1.4, color: AppColors.ink2)),
            ],
          ),
        ),
      ],
    );
  }
}
