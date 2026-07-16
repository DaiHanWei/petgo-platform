import 'package:flutter/material.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:go_router/go_router.dart';

import '../../../core/router/route_intent.dart';
import '../../../core/theme/colors.dart';
import '../../../core/theme/rounded.dart';
import '../../../core/theme/shadows.dart';
import '../../../core/theme/spacing.dart';
import '../../../core/theme/typography.dart';
import '../../../l10n/app_localizations.dart';
import '../../../shared/widgets/dashed_rect.dart';
import '../../../shared/widgets/design/baru_badge.dart';
import '../../../shared/widgets/design/online_pulse_dot.dart';
import '../../auth/domain/auth_guard.dart';
import '../../auth/domain/auth_state.dart';
import '../../consult/data/consult_repository.dart';
import '../../consult/domain/consult_history_item.dart';
import '../../consult/domain/consult_session.dart';
import '../../consult/presentation/consult_rating_dialog.dart';
import '../../consult/presentation/consult_refresh.dart';

/// Konsultasi Kilat 问诊 hub（TailTopia Prototype 换肤 · Story 4.3 + 5.8）。
///
/// 从上至下：① Momo 头部 ② AI 分诊 / 兽医咨询**平级**双入口卡 ③ 在线兽医条
/// ④ 进行中会话卡（若有）⑤ 我的问诊历史。进 Tab 查 5.6 补弹评分。
class TriagePage extends ConsumerStatefulWidget {
  const TriagePage({super.key});

  @override
  ConsumerState<TriagePage> createState() => _TriagePageState();
}

class _TriagePageState extends ConsumerState<TriagePage> {
  Future<ConsultSession?>? _active;
  Future<ConsultHistoryPage>? _history;
  bool _promptChecked = false;

  @override
  void initState() {
    super.initState();
    if (ref.read(authControllerProvider).isLoggedIn) {
      _load();
      WidgetsBinding.instance.addPostFrameCallback((_) => _checkPendingRating());
    }
  }

  void _load() {
    final repo = ref.read(consultRepositoryProvider);
    _active = repo.active();
    _history = repo.history();
  }

  /// 下拉刷新：重拉进行中会话 + 问诊历史（聊天列表）。等两条 future 落定再收起刷新圈。
  /// 受控 Tab 已保证登录态；游客兜底直接返回不打接口。
  Future<void> _refresh() async {
    if (!ref.read(authControllerProvider).isLoggedIn) return;
    setState(_load);
    final a = _active, h = _history;
    await Future.wait<void>([
      if (a != null) a.then((_) {}).catchError((_) {}),
      if (h != null) h.then((_) {}).catchError((_) {}),
    ]);
  }

  /// 兽医卡：登录态读 [consultAvailabilityProvider] 的在线 bool 驱动绿点（在线→脉冲「Dokter tersedia」，
  /// 否则静态营业时段提示）；游客不打 availability 请求，显示默认营业时段（不造假具名医生）。
  Widget _vetCard(BuildContext context, WidgetRef ref, AppLocalizations l10n, bool loggedIn) {
    final online =
        loggedIn && (ref.watch(consultAvailabilityProvider).asData?.value.vetOnline ?? false);
    return _KCard(
      ctaKey: 'triageEntryVet',
      emoji: '🩺',
      ai: false,
      title: l10n.triageVetCardTitle,
      desc: l10n.triageVetCardDesc,
      dotText: online ? l10n.triageVetAvailableNow : l10n.triageVetHours,
      pulse: online,
      cta: l10n.triageVetCardCta,
      onTap: () => requireLogin(
        ref,
        context,
        pendingAction: const RouteIntent(location: '/consult'),
        onAllowed: () => context.push('/consult'),
      ),
    );
  }

  Future<void> _checkPendingRating() async {
    if (_promptChecked) return;
    _promptChecked = true;
    try {
      final pending = await ref.read(consultRepositoryProvider).pendingRating();
      if (pending == null || !mounted) return;
      final result = await ConsultRatingDialog.show(context);
      if (result == null) {
        await ref.read(consultRepositoryProvider).markRatingPrompted(pending.id);
      } else {
        await ref.read(consultRepositoryProvider).rate(pending.id, result.stars, result.comment);
        if (mounted) setState(_load);
      }
    } catch (_) {
      // 补弹失败静默（下次进页再查）。
    }
  }

  @override
  Widget build(BuildContext context) {
    // 任意入口评分后 → 重拉历史列表（否则停在「未评分」）。
    ref.listen<int>(consultRefreshProvider, (_, _) {
      if (mounted) setState(_load);
    });
    final l10n = AppLocalizations.of(context);
    final loggedIn = ref.watch(authControllerProvider).isLoggedIn;
    return Scaffold(
      backgroundColor: AppColors.cream,
      body: SafeArea(
        bottom: false,
        child: RefreshIndicator(
          onRefresh: _refresh,
          color: AppColors.mint, // 品牌主色 #845EC9
          child: ListView(
            physics: const AlwaysScrollableScrollPhysics(), // 内容不足一屏也可下拉
            padding: const EdgeInsets.fromLTRB(18, 12, 18, 24),
            children: <Widget>[
            // ① 文案 Hero（原型 khero）。
            Text(l10n.triageHeroTitle,
                style: const TextStyle(
                    fontSize: 23,
                    height: 1.2,
                    fontWeight: FontWeight.w900,
                    letterSpacing: -0.4,
                    color: AppColors.ink)),
            const SizedBox(height: 4),
            Text(l10n.triageHeroSubtitle,
                style: const TextStyle(fontSize: 13, color: AppColors.muted)),
            const SizedBox(height: 16),
            // ② AI 分诊卡（原型 kcard-ai：135° 紫渐变 + ⚡ + 白字 + 绿点常驻态 + 白底 CTA）。
            _KCard(
              ctaKey: 'triageEntryAI',
              emoji: '⚡',
              ai: true,
              title: l10n.triageAiCardTitle,
              desc: l10n.triageAiCardDesc,
              dotText: l10n.triageAiAlwaysOn,
              pulse: true,
              cta: l10n.triageAiCardCta,
              onTap: () => requireLogin(
                ref,
                context,
                pendingAction: const RouteIntent(location: '/triage/upload'),
                onAllowed: () => context.push('/triage/upload'),
              ),
            ),
            const SizedBox(height: 14),
            // ② 兽医咨询卡（原型 kcard-vet：白底 + #E6E6E6 边框 + 🩺 + availability 绿点 + 营业时段）。
            _vetCard(context, ref, l10n, loggedIn),
            const SizedBox(height: 18),
            if (loggedIn) ...<Widget>[
              // ④ 进行中会话卡（若有）。
              FutureBuilder<ConsultSession?>(
                future: _active,
                builder: (context, snapshot) {
                  final s = snapshot.data;
                  if (s == null) return const SizedBox.shrink();
                  final target =
                      s.isWaiting ? '/consult/waiting/${s.id}' : '/consult/conversation/${s.id}';
                  return Padding(
                    padding: const EdgeInsets.only(top: AppSpacing.lg),
                    child: Material(
                      color: AppColors.skyTint,
                      borderRadius: BorderRadius.circular(AppRounded.lg),
                      child: InkWell(
                        key: const ValueKey('consultActiveCard'),
                        borderRadius: BorderRadius.circular(AppRounded.lg),
                        onTap: () => context.push(target),
                        child: Padding(
                          padding: const EdgeInsets.all(AppSpacing.lg),
                          child: Row(
                            children: [
                              const Icon(Icons.forum_outlined, color: AppColors.sky),
                              const SizedBox(width: AppSpacing.md),
                              Expanded(child: Text(l10n.consultActiveCard, style: AppTypography.body)),
                              const Icon(Icons.chevron_right, color: AppColors.textTertiary),
                            ],
                          ),
                        ),
                      ),
                    ),
                  );
                },
              ),
              // ⑤ 我的问诊历史列表。
              const SizedBox(height: AppSpacing.section),
              Text(l10n.consultHistoryTitle, style: AppTypography.title),
              const SizedBox(height: AppSpacing.md),
              FutureBuilder<ConsultHistoryPage>(
                future: _history,
                builder: (context, snapshot) {
                  if (snapshot.connectionState == ConnectionState.waiting) {
                    return const SizedBox(height: AppSpacing.lg);
                  }
                  final items = snapshot.data?.items ?? const <ConsultHistoryItem>[];
                  if (items.isEmpty) {
                    return Padding(
                      padding: const EdgeInsets.symmetric(vertical: AppSpacing.lg),
                      child: Text(l10n.consultHistoryEmpty,
                          key: const ValueKey('consultHistoryEmpty'), style: AppTypography.caption),
                    );
                  }
                  return Column(children: [for (final it in items) _HistoryTile(item: it)]);
                },
              ),
            ],
          ],
          ),
        ),
      ),
    );
  }
}

/// 问诊入口卡（原型 kcard）：AI 态=135° 紫渐变 + 白字；兽医态=白底 + #E6E6E6 边框。
/// 图标盒 + 标题 + 描述 + 绿点行（可脉冲）+ 全宽 CTA（带「→」）。
class _KCard extends StatelessWidget {
  const _KCard({
    required this.ctaKey,
    required this.emoji,
    required this.ai,
    required this.title,
    required this.desc,
    required this.dotText,
    required this.pulse,
    required this.cta,
    required this.onTap,
  });

  final String ctaKey;
  final String emoji;
  final bool ai;
  final String title;
  final String desc;
  final String dotText;
  final bool pulse;
  final String cta;
  final VoidCallback onTap;

  @override
  Widget build(BuildContext context) {
    final onCard = ai ? AppColors.onAccent : AppColors.ink;
    final subColor = ai ? AppColors.onAccent.withValues(alpha: 0.85) : AppColors.ink2;
    final card = Container(
      padding: const EdgeInsets.all(18),
      decoration: BoxDecoration(
        gradient: ai
            ? const LinearGradient(
                begin: Alignment.topLeft,
                end: Alignment.bottomRight,
                colors: [AppColors.mint, AppColors.mint500],
              )
            : null,
        color: ai ? null : AppColors.card,
        borderRadius: BorderRadius.circular(20),
        // 兽医卡描边改由外层 CustomPaint 画紫虚线（0711），故此处不设实线 border。
        boxShadow: AppShadows.md,
      ),
      child: Column(
        // start 对齐：否则 stretch 会用 tight 约束把宽 46 的图标盒撑满整卡宽（呈满宽横条）。
        // CTA 按钮自带 SizedBox(width: double.infinity) 仍能撑满，不受影响。
        crossAxisAlignment: CrossAxisAlignment.start,
        children: [
          Container(
            width: 46,
            height: 46,
            alignment: Alignment.center,
            decoration: BoxDecoration(
              color: ai ? AppColors.onAccent.withValues(alpha: 0.2) : AppColors.skyTint,
              borderRadius: BorderRadius.circular(13),
            ),
            child: Text(emoji, style: const TextStyle(fontSize: 22)),
          ),
          const SizedBox(height: 11),
          Text(title,
              style: TextStyle(fontSize: 17, fontWeight: FontWeight.w700, color: onCard)),
          const SizedBox(height: 3),
          Text(desc, style: TextStyle(fontSize: 12, height: 1.5, color: subColor)),
          const SizedBox(height: 13),
          Row(
            children: [
              OnlinePulseDot(size: 7, color: AppColors.triageGreen, pulsing: pulse),
              const SizedBox(width: 6),
              Flexible(
                child: Text(dotText,
                    style: TextStyle(
                        fontSize: 11,
                        color: ai ? AppColors.onAccent.withValues(alpha: 0.9) : AppColors.ink2)),
              ),
            ],
          ),
          const SizedBox(height: 13),
          // CTA：AI=白底紫字；兽医=描边紫字。文案保持 arb，箭头作为独立图标（测试按文案断言）。
          _CtaButton(ctaKey: ctaKey, label: cta, ai: ai, onTap: onTap),
        ],
      ),
    );
    if (ai) return card;
    // 兽医卡（0711 konsultasi-entries）：紫虚线描边 + 右上 BARU 徽章，突出「免费入队」。
    return Stack(
      clipBehavior: Clip.none,
      children: [
        CustomPaint(
          foregroundPainter: DashedRRectPainter(
            color: AppColors.mint,
            radius: 20,
            dash: 6,
            gap: 4,
            strokeWidth: 1.5,
          ),
          child: card,
        ),
        const Positioned(top: -7, right: 14, child: BaruBadge()),
      ],
    );
  }
}

/// 入口卡 CTA 按钮（原型 kbtn）：AI 白底紫字 / 兽医白底紫字描边，末尾「→」。
class _CtaButton extends StatelessWidget {
  const _CtaButton(
      {required this.ctaKey, required this.label, required this.ai, required this.onTap});

  final String ctaKey;
  final String label;
  final bool ai;
  final VoidCallback onTap;

  @override
  Widget build(BuildContext context) {
    return SizedBox(
      width: double.infinity,
      child: ai
          ? FilledButton(
              key: ValueKey(ctaKey),
              onPressed: onTap,
              style: FilledButton.styleFrom(
                backgroundColor: AppColors.card,
                foregroundColor: AppColors.mint,
                elevation: 0,
                padding: const EdgeInsets.symmetric(vertical: 11),
                shape: RoundedRectangleBorder(borderRadius: BorderRadius.circular(11)),
              ),
              child: _label(),
            )
          : OutlinedButton(
              key: ValueKey(ctaKey),
              onPressed: onTap,
              style: OutlinedButton.styleFrom(
                foregroundColor: AppColors.mint,
                side: const BorderSide(color: AppColors.mint, width: 1.5),
                padding: const EdgeInsets.symmetric(vertical: 11),
                shape: RoundedRectangleBorder(borderRadius: BorderRadius.circular(11)),
              ),
              child: _label(),
            ),
    );
  }

  Widget _label() => Row(
        mainAxisAlignment: MainAxisAlignment.center,
        mainAxisSize: MainAxisSize.min,
        children: [
          Text(label, style: const TextStyle(fontSize: 13, fontWeight: FontWeight.w700)),
          const SizedBox(width: 6),
          const Icon(Icons.arrow_forward_rounded, size: 16),
        ],
      );
}

/// 历史条目（Story 5.8 F2）：头像 + 严重度胶囊/星评 + 相对时间 + 摘要。
class _HistoryTile extends StatelessWidget {
  const _HistoryTile({required this.item});

  final ConsultHistoryItem item;

  static String _relativeTime(AppLocalizations l10n, DateTime t) {
    final d = DateTime.now().difference(t);
    if (d.inMinutes < 1) return l10n.timeJustNow;
    if (d.inHours < 1) return l10n.timeMinutesAgo(d.inMinutes);
    if (d.inDays < 1) return l10n.timeHoursAgo(d.inHours);
    return l10n.timeDaysAgo(d.inDays);
  }

  @override
  Widget build(BuildContext context) {
    final l10n = AppLocalizations.of(context);
    final isAi = item.isAi;
    final time = item.date == null ? '' : _relativeTime(l10n, item.date!);
    final summary = isAi ? (item.symptomSummary ?? '') : _vetSubtitle(l10n);

    final List<Widget> meta = <Widget>[
      if (isAi)
        _SeverityChip(level: item.dangerLevel)
      else
        Expanded(
          child: Text(item.vetDisplayName ?? l10n.historyTypeVet,
              style: AppTypography.body.copyWith(fontWeight: FontWeight.w600),
              maxLines: 1,
              overflow: TextOverflow.ellipsis),
        ),
      if (!isAi && item.userStars != null) ...<Widget>[
        const SizedBox(width: AppSpacing.sm),
        _Stars(count: item.userStars!),
      ],
      if (isAi) const Spacer(),
      if (time.isNotEmpty) ...<Widget>[
        const SizedBox(width: AppSpacing.sm),
        Text(time, style: AppTypography.micro),
      ],
    ];

    return Card(
      margin: const EdgeInsets.only(bottom: AppSpacing.sm),
      child: ListTile(
        key: ValueKey(isAi ? 'historyAi_${item.triageId}' : 'historyVet_${item.sessionId}'),
        leading: _HistoryAvatar(isAi: isAi, vetName: item.vetDisplayName),
        title: Row(children: meta),
        subtitle: summary.isEmpty
            ? null
            : Text(summary, maxLines: 2, overflow: TextOverflow.ellipsis),
        onTap: () {
          if (isAi) {
            // AI 分诊历史 → 只读结果快照（bug 20260702-238/228）。带症状摘要避免结果视图回退串味。
            if (item.triageId != null) {
              context.push('/triage/result/${item.triageId}', extra: item.symptomSummary);
            }
            return;
          }
          if (item.sessionId != null) {
            context.push('/consult/conversation/${item.sessionId}');
          }
        },
      ),
    );
  }

  String _vetSubtitle(AppLocalizations l10n) {
    final parts = <String>[];
    if (item.terminalState == 'PENDING_CLOSE') {
      // 兽医已结束、30min 续聊窗口：归历史但标记仍可继续（点进可在窗口内续聊）。
      parts.add(l10n.consultHistoryStillOpen);
    } else if (item.terminalState == 'INTERRUPTED') {
      parts.add(l10n.terminalInterrupted);
    } else if (item.userStars == null) {
      parts.add(l10n.historyUnrated);
    }
    if (item.archived == true) parts.add(l10n.historyArchived);
    if (item.sessionSummary != null && item.sessionSummary!.isNotEmpty) {
      parts.add(item.sessionSummary!);
    }
    return parts.join(' · ');
  }
}

/// 历史项头像：AI → 🤖 浅蓝圆；兽医 → 昵称首字母浅蓝圆。
class _HistoryAvatar extends StatelessWidget {
  const _HistoryAvatar({required this.isAi, this.vetName});

  final bool isAi;
  final String? vetName;

  @override
  Widget build(BuildContext context) {
    final bg = AppColors.sky.withValues(alpha: 0.15);
    if (isAi) {
      return CircleAvatar(
          radius: 18, backgroundColor: bg, child: const Text('🤖', style: TextStyle(fontSize: 18)));
    }
    final n = (vetName ?? '').trim();
    return CircleAvatar(
      radius: 18,
      backgroundColor: bg,
      child: n.isEmpty
          ? const Icon(Icons.medical_services_outlined, size: 18, color: AppColors.sky)
          : Text(n.characters.first.toUpperCase(),
              style: const TextStyle(
                  fontSize: 15, fontWeight: FontWeight.w700, color: AppColors.mint700)),
    );
  }
}

/// 分诊严重度胶囊（GREEN/YELLOW/RED → 三色 + 本地化标签）。
class _SeverityChip extends StatelessWidget {
  const _SeverityChip({required this.level});

  final String? level;

  @override
  Widget build(BuildContext context) {
    final l10n = AppLocalizations.of(context);
    final (Color color, String label) = switch (level) {
      'RED' => (AppColors.triageRed, l10n.triageLevelRed),
      'YELLOW' => (AppColors.triageYellow, l10n.triageLevelYellow),
      _ => (AppColors.triageGreen, l10n.triageLevelGreen),
    };
    return Container(
      padding: const EdgeInsets.symmetric(horizontal: AppSpacing.sm, vertical: AppSpacing.xxs),
      decoration: BoxDecoration(color: color, borderRadius: BorderRadius.circular(AppRounded.full)),
      child: Text(label, style: AppTypography.badge),
    );
  }
}

/// 兽医星评（1..5 实心星，其余空星）。
class _Stars extends StatelessWidget {
  const _Stars({required this.count});

  final int count;

  @override
  Widget build(BuildContext context) {
    final c = count.clamp(0, 5);
    return Row(
      mainAxisSize: MainAxisSize.min,
      children: <Widget>[
        for (int i = 1; i <= 5; i++)
          Icon(i <= c ? Icons.star_rounded : Icons.star_outline_rounded,
              size: 14, color: AppColors.triageYellow),
      ],
    );
  }
}
