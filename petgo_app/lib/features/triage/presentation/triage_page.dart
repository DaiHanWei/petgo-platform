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
import '../../../shared/widgets/design/btn3d.dart';
import '../../../shared/widgets/design/emoji_avatar.dart';
import '../../../shared/widgets/design/momo.dart';
import '../../auth/domain/auth_guard.dart';
import '../../auth/domain/auth_state.dart';
import '../../consult/data/consult_repository.dart';
import '../../consult/domain/consult_history_item.dart';
import '../../consult/domain/consult_session.dart';
import '../../consult/presentation/consult_rating_dialog.dart';

/// Konsultasi Kilat 问诊 hub（PetGo Prototype 换肤 · Story 4.3 + 5.8）。
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
    final l10n = AppLocalizations.of(context);
    final loggedIn = ref.watch(authControllerProvider).isLoggedIn;
    return Scaffold(
      backgroundColor: AppColors.cream,
      body: SafeArea(
        bottom: false,
        child: ListView(
          padding: const EdgeInsets.fromLTRB(18, 12, 18, 24),
          children: <Widget>[
            // ① Momo 头部。
            Row(
              children: const [
                Momo(size: 44, happy: false),
                SizedBox(width: 10),
                Expanded(
                  child: Column(
                    crossAxisAlignment: CrossAxisAlignment.start,
                    children: [
                      Text('Konsultasi Kilat',
                          style: TextStyle(
                              fontSize: 24,
                              fontWeight: FontWeight.w900,
                              letterSpacing: -0.4,
                              color: AppColors.ink)),
                      Text('Tenang, kami bantu cek anabul-mu 💚',
                          style: TextStyle(fontSize: 13, color: AppColors.muted)),
                    ],
                  ),
                ),
              ],
            ),
            const SizedBox(height: 14),
            // ② AI 分诊（soft CTA）。
            _EntryCard(
              ctaKey: 'triageEntryAI',
              emoji: '🤖',
              tone: AppColors.mintTint,
              title: 'Tanya AI (Triase)',
              badge: _EntryBadge(label: '≤ 15 detik', color: AppColors.mint700),
              desc:
                  'Unggah foto gejala, AI kasih level bahaya + saran observasi & obat rumahan.',
              cta: 'Mulai triase',
              primary: false,
              onTap: () => requireLogin(
                ref,
                context,
                pendingAction: const RouteIntent(location: '/triage/upload'),
                onAllowed: () => context.push('/triage/upload'),
              ),
            ),
            const SizedBox(height: 14),
            // ② 兽医咨询（primary CTA）。
            _EntryCard(
              ctaKey: 'triageEntryVet',
              emoji: '🩺',
              tone: AppColors.skyTint,
              title: 'Chat Dokter Hewan',
              badge: const _EntryBadge(label: '2 dokter online', color: Color(0xFF2F7DB8), live: true),
              desc: 'Ngobrol langsung (teks & foto) dengan dokter mitra. Gratis di versi ini.',
              cta: 'Mulai konsultasi',
              primary: true,
              onTap: () => requireLogin(
                ref,
                context,
                pendingAction: const RouteIntent(location: '/consult'),
                onAllowed: () => context.push('/consult'),
              ),
            ),
            const SizedBox(height: 18),
            // ③ 在线兽医条。
            const Text('Dokter sedang online',
                style: TextStyle(fontSize: 15, fontWeight: FontWeight.w900, color: AppColors.ink)),
            const SizedBox(height: 10),
            const _VetRow(emoji: '🧑‍⚕️', name: 'drh. Sari', spec: 'Kucing & Anjing', online: true),
            const SizedBox(height: 10),
            const _VetRow(emoji: '👨‍⚕️', name: 'drh. Bayu', spec: 'Eksotik & Burung', online: true),
            const SizedBox(height: 10),
            const _VetRow(emoji: '👩‍⚕️', name: 'drh. Indah', spec: 'Umum', online: false),
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
    );
  }
}

/// 问诊入口卡（PetGo Prototype EntryCard）：图标盒 + 标题/中文 + 徽章 + 描述 + 全宽 CTA。
class _EntryCard extends StatelessWidget {
  const _EntryCard({
    required this.ctaKey,
    required this.emoji,
    required this.tone,
    required this.title,
    required this.badge,
    required this.desc,
    required this.cta,
    required this.primary,
    required this.onTap,
  });

  final String ctaKey;
  final String emoji;
  final Color tone;
  final String title;
  final Widget badge;
  final String desc;
  final String cta;
  final bool primary;
  final VoidCallback onTap;

  @override
  Widget build(BuildContext context) {
    return Container(
      padding: const EdgeInsets.all(16),
      decoration: BoxDecoration(
        color: AppColors.card,
        borderRadius: BorderRadius.circular(24),
        boxShadow: AppShadows.md,
      ),
      child: Column(
        crossAxisAlignment: CrossAxisAlignment.stretch,
        children: [
          Row(
            crossAxisAlignment: CrossAxisAlignment.start,
            children: [
              Container(
                width: 52,
                height: 52,
                alignment: Alignment.center,
                decoration: BoxDecoration(color: tone, borderRadius: BorderRadius.circular(16)),
                child: Text(emoji, style: const TextStyle(fontSize: 27)),
              ),
              const SizedBox(width: 13),
              Expanded(
                child: Column(
                  crossAxisAlignment: CrossAxisAlignment.start,
                  children: [
                    Wrap(
                      crossAxisAlignment: WrapCrossAlignment.center,
                      spacing: 8,
                      children: [
                        Text(title,
                            style: const TextStyle(
                                fontSize: 17, fontWeight: FontWeight.w900, letterSpacing: -0.2)),
                      ],
                    ),
                    const SizedBox(height: 6),
                    badge,
                  ],
                ),
              ),
            ],
          ),
          const SizedBox(height: 12),
          Text(desc,
              style: const TextStyle(fontSize: 13.5, color: AppColors.ink2, height: 1.5)),
          const SizedBox(height: 14),
          Btn3d(
            key: ValueKey(ctaKey),
            expand: true,
            variant: primary ? Btn3dVariant.primary : Btn3dVariant.soft,
            fontSize: 15,
            onPressed: onTap,
            child: Text(cta),
          ),
        ],
      ),
    );
  }
}

/// 入口卡徽章（白底胶囊 + 可选在线绿点）。
class _EntryBadge extends StatelessWidget {
  const _EntryBadge({required this.label, required this.color, this.live = false});

  final String label;
  final Color color;
  final bool live;

  @override
  Widget build(BuildContext context) {
    return Container(
      padding: const EdgeInsets.symmetric(horizontal: 9, vertical: 4),
      decoration: BoxDecoration(
        color: AppColors.card,
        borderRadius: BorderRadius.circular(999),
        boxShadow: AppShadows.sm,
      ),
      child: Row(
        mainAxisSize: MainAxisSize.min,
        children: [
          if (live) ...[
            Container(
                width: 7,
                height: 7,
                decoration: const BoxDecoration(color: AppColors.mint, shape: BoxShape.circle)),
            const SizedBox(width: 5),
          ],
          Text(label,
              style: TextStyle(fontSize: 11.5, fontWeight: FontWeight.w800, color: color)),
        ],
      ),
    );
  }
}

/// 在线兽医条目（头像 + 在线点 + 姓名/专长 + 状态）。
class _VetRow extends StatelessWidget {
  const _VetRow(
      {required this.emoji, required this.name, required this.spec, required this.online});

  final String emoji;
  final String name;
  final String spec;
  final bool online;

  @override
  Widget build(BuildContext context) {
    return Container(
      padding: const EdgeInsets.symmetric(horizontal: 14, vertical: 12),
      decoration: BoxDecoration(
        color: AppColors.card,
        borderRadius: BorderRadius.circular(24),
        boxShadow: AppShadows.md,
      ),
      child: Row(
        children: [
          Stack(
            children: [
              EmojiAvatar(emoji: emoji, size: 42, tone: AppColors.cream2),
              Positioned(
                right: 0,
                bottom: 0,
                child: Container(
                  width: 12,
                  height: 12,
                  decoration: BoxDecoration(
                    color: online ? AppColors.mint : AppColors.line,
                    shape: BoxShape.circle,
                    border: Border.all(color: AppColors.card, width: 2.5),
                  ),
                ),
              ),
            ],
          ),
          const SizedBox(width: 12),
          Expanded(
            child: Column(
              crossAxisAlignment: CrossAxisAlignment.start,
              children: [
                Text(name,
                    style: const TextStyle(fontSize: 14.5, fontWeight: FontWeight.w800)),
                Text(spec, style: const TextStyle(fontSize: 12.5, color: AppColors.muted)),
              ],
            ),
          ),
          Text(
            online ? 'Online' : 'Sibuk',
            style: TextStyle(
                fontSize: 12,
                fontWeight: FontWeight.w700,
                color: online ? AppColors.mint700 : AppColors.muted),
          ),
        ],
      ),
    );
  }
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
    if (item.terminalState == 'INTERRUPTED') {
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
