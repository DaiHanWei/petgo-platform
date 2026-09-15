import 'package:dio/dio.dart';
import 'package:flutter/material.dart';
import 'package:flutter/services.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:url_launcher/url_launcher.dart';

import '../../../core/theme/colors.dart';
import '../../../core/theme/spacing.dart';
import '../../../core/theme/typography.dart';
import '../../../l10n/app_localizations.dart';
import '../../../shared/widgets/app_image.dart';
import '../../../shared/widgets/app_toast.dart';
import '../../../shared/widgets/empty_state.dart';
import '../../../shared/widgets/letter_avatar.dart';
import '../../../shared/widgets/mini_profile_sheet.dart';
import '../../content/presentation/report_sheet.dart';
import '../../profile/domain/share_service.dart';
import '../data/place_repository.dart';
import '../domain/place_detail.dart';
import 'place_distance_format.dart';
import 'place_labels.dart';
import 'place_location_controller.dart';
import 'place_mini_map.dart';

/// 场所详情页（V1.3.0 batch-b1 Story 1.5 · UI 稿 A4）。
///
/// <h2>🔴 反向验收：本页**没有**这些东西（FR-112.6 / AC6）</h2>
/// 没有收藏、没有评分打星、没有营业时间/电话等商户字段、**没有打卡按钮**（⑧ 在批次 B2）、
/// **没有「编辑场所」入口**（本版用户不可编辑，2026-09-15 拍板；纠错走后台 AB-17A）。
/// 这不是「还没做」，是明确不做 —— 后端 DTO 里连字段都没有（那侧有契约测试钉着）。
///
/// <h2>范围边界（别顺手加）</h2>
/// <ul>
///   <li>**点照片看大图**归 Story 1.6（那条把既有私有灯箱抽成公共组件）—— 本页照片<b>暂不可点</b>；</li>
///   <li>**评论区 + 二元态度**归 Story 1.7；本页只显示评论数（1.7 前恒 0）；</li>
///   <li>**分享出 H5 链接**归 Story 1.10 —— 本页的分享按钮只分享「名称 + 地址」文本（见 `_onShare`）。</li>
/// </ul>
///
/// <h2>⚠️ 标记人点击是有意的临时方案（AC4）</h2>
/// 本 story 内点标记人走**现有迷你主页卡**。写成「进公开主页」会让本 story 依赖 Epic 2
/// （主页还不存在）—— Epic 2 的 Story 2-1 会统一收口三处入口，那时本页一并改掉。
/// **别在这里提前接主页。**
class PlaceDetailPage extends ConsumerWidget {
  const PlaceDetailPage({super.key, required this.token});

  final String token;

  /// 路由路径模板。⚠️ 与 `app_router.dart` 的注册值同源。
  static const String routePattern = '/places/:token';

  static String routeFor(String token) => '/places/$token';

  @override
  Widget build(BuildContext context, WidgetRef ref) {
    final l10n = AppLocalizations.of(context);
    final locationAsync = ref.watch(placeLocationProvider);

    // 🔴 **先等定位态读出来再发请求**（同列表页那道闸门，code-review 2026-09-15）。
    // 这只是一次不弹窗的权限状态查询 + 可能一次取缓存定点，不是 5 秒的空转。
    // 不等的代价是两条：① 先发一次无坐标请求、定位落定后族键变化又发第二次；
    // ② 族键换了就是**另一个还没有值的 provider** —— 已经渲染好的详情会被整屏转圈顶掉，
    // 第二次再失败就直接落错误态。深链进来（Story 1.10 的分享链接）恰好总是这条路径。
    if (locationAsync.isLoading && !locationAsync.hasValue) {
      return _scaffold(l10n, const Center(child: CircularProgressIndicator()));
    }
    // 定位链路失败不该让详情打不开 —— 退回不带坐标（距离位隐藏）。
    final coords = locationAsync.value?.coordinates;
    // 坐标按 ~110 m 归一后才做族键（见 placeDetailQueryFor）。
    final query = placeDetailQueryFor(token, coords?.latitude, coords?.longitude);
    final async = ref.watch(placeDetailProvider(query));

    return _scaffold(
      l10n,
      switch (async) {
        // 🔴 404（下架 / 不存在）走**统一空态**，不区分两种情况、不泄漏任何原内容（AC7）。
        AsyncError(:final error) => _errorBody(context, ref, l10n, query, error),
        AsyncData(:final value) => _body(context, ref, l10n, value),
        _ => const Center(child: CircularProgressIndicator()),
      },
      // 举报入口（AC5）。⚠️ 只在**真的有这个场所**时才给 —— 下架态弹举报抽屉毫无意义。
      onReport: async.hasValue ? () => _onReport(context, ref) : null,
    );
  }

  Widget _scaffold(AppLocalizations l10n, Widget body, {VoidCallback? onReport}) =>
      Scaffold(
        backgroundColor: AppColors.cream,
        appBar: AppBar(
          backgroundColor: AppColors.cream,
          scrolledUnderElevation: 0,
          title: Text(l10n.placeDetailTitle, style: AppTypography.title),
          actions: [
            if (onReport != null)
              IconButton(
                key: const ValueKey('placeDetailMore'),
                tooltip: l10n.placeDetailReport,
                onPressed: onReport,
                icon: const Icon(Icons.more_horiz),
              ),
          ],
        ),
        body: body,
      );

  Widget _errorBody(BuildContext context, WidgetRef ref, AppLocalizations l10n,
      PlaceDetailQuery query, Object error) {
    final status = error is DioException ? error.response?.statusCode : null;
    // 404 = 下架或不存在 → 统一「场所不存在」空态（无重试按钮：重试一万次也还是没有）。
    if (status == 404) {
      return EmptyState(
        title: l10n.placeDetailGoneTitle,
        message: l10n.placeDetailGoneBody,
        icon: Icons.place_outlined,
      );
    }
    // 其它失败（网络 / 5xx）→ 可重试（F13）。
    return EmptyState(
      title: l10n.placeErrorTitle,
      message: l10n.placeErrorBody,
      icon: Icons.cloud_off_rounded,
      actionLabel: l10n.placeRetry,
      onAction: () => ref.invalidate(placeDetailProvider(query)),
    );
  }

  Widget _body(
      BuildContext context, WidgetRef ref, AppLocalizations l10n, PlaceDetail p) {
    final subtitle = [
      if (p.type != null) p.type!.label(l10n),
      if (p.distanceMeters != null) formatPlaceDistance(l10n, p.distanceMeters!),
    ].join(' · ');

    return ListView(
      padding: const EdgeInsets.only(bottom: AppSpacing.xl),
      children: [
        _PhotoStrip(urls: p.photoUrls),
        Padding(
          padding: const EdgeInsets.symmetric(horizontal: AppSpacing.lg),
          child: Column(
            crossAxisAlignment: CrossAxisAlignment.start,
            children: [
              const SizedBox(height: AppSpacing.md),
              Text(p.name, style: AppTypography.headline),
              if (subtitle.isNotEmpty) ...[
                const SizedBox(height: AppSpacing.xs),
                Text(subtitle, style: AppTypography.caption),
              ],
              if (p.tags.isNotEmpty) ...[
                const SizedBox(height: AppSpacing.sm),
                Wrap(
                  spacing: AppSpacing.xs,
                  runSpacing: AppSpacing.xs,
                  // 详情页给**全部**标签（列表页才截断到 2 个 +N）。
                  children: [for (final t in p.tags) _TagChip(label: t.label(l10n))],
                ),
              ],
              if (p.description != null) ...[
                const SizedBox(height: AppSpacing.md),
                Text(p.description!, style: AppTypography.body),
              ],
              const SizedBox(height: AppSpacing.md),
              _AddressRow(
                address: p.addressText,
                onCopy: () => _onCopyAddress(context, l10n, p.addressText),
              ),
              const SizedBox(height: AppSpacing.sm),
              // 定位小地图 +「在地图中打开」（AD-3 允许的两处之一）。
              PlaceMiniMap(
                latitude: p.latitude,
                longitude: p.longitude,
                name: p.name,
                onOpenExternal: () => _onOpenInMaps(context, l10n, p),
              ),
              const SizedBox(height: AppSpacing.md),
              _MarkerRow(
                marker: p.markedBy,
                // AC4：本 story 走**现有迷你主页卡**（Epic 2 统一收口时一并改）。
                onTap: p.markedBy.tappable
                    ? () => showMiniProfile(context, ref, p.markedBy.userId)
                    : null,
              ),
              const SizedBox(height: AppSpacing.md),
              _ShareButton(onTap: (origin) => _onShare(context, ref, l10n, p, origin)),
              const SizedBox(height: AppSpacing.lg),
              // 评论数（Story 1.7 接真实评论区；这里只是那个数字）。
              _CountsRow(detail: p),
            ],
          ),
        ),
      ],
    );
  }

  /// AC2：地址进剪贴板 + 轻提示。
  Future<void> _onCopyAddress(
      BuildContext context, AppLocalizations l10n, String address) async {
    await Clipboard.setData(ClipboardData(text: address));
    if (!context.mounted) return;
    showAppToast(context, l10n.placeDetailAddressCopied);
  }

  /// AC3：跳系统地图 App 并定位到该坐标。
  ///
  /// 🔴 **这是普通跳转链接，不是 API 调用、不计费**（AD-3 Rule 5）。
  /// 用 `geo:` 优先（安卓会让用户选系统里装的地图 App），失败回落 Google Maps 网页 URL
  /// （iOS 上 `geo:` 通常无人接管）。**两条都不是 Directions API** —— 没有请求、没有密钥。
  ///
  /// ⚠️ 安卓侧还需要 `AndroidManifest.xml` 的 `<queries>` 里声明 `geo:` 的 VIEW intent，
  /// 否则 Android 11+ 的包可见性会让 [canLaunchUrl] 恒返回 false（那条已经加了）。
  ///
  /// 🔴 **必须看 [launchUrl] 的返回值**：没有 activity 接管时 url_launcher 是
  /// `return false` 而**不是抛异常** —— 只靠 `catch` 的话最常见的那条失败路径上
  /// 用户点了完全没反应，而提示文案是死代码（code-review 2026-09-15）。
  Future<void> _onOpenInMaps(
      BuildContext context, AppLocalizations l10n, PlaceDetail p) async {
    final label = Uri.encodeComponent(p.name);
    final geo = Uri.parse('geo:${p.latitude},${p.longitude}?q='
        '${p.latitude},${p.longitude}($label)');
    final web = Uri.parse(
        'https://www.google.com/maps/search/?api=1&query=${p.latitude},${p.longitude}');
    bool opened = false;
    try {
      if (await canLaunchUrl(geo)) {
        opened = await launchUrl(geo, mode: LaunchMode.externalApplication);
      }
      // geo: 没人接管（iOS）或启动被拒 → 回落网页链接。
      if (!opened) {
        opened = await launchUrl(web, mode: LaunchMode.externalApplication);
      }
    } catch (_) {
      opened = false;
    }
    if (!opened && context.mounted) {
      showAppToast(context, l10n.placeDetailOpenMapsFailed);
    }
  }

  /// 分享（AC1 要求按钮在位）。
  ///
  /// ⚠️ **本 story 只分享「名称 + 地址」文本** —— H5 场所页链接是 Story 1.10 的交付物，
  /// 那条落地后把这里的 payload 换成链接即可（按钮、埋点位置都不用动）。
  /// 刻意不做成一个点了没反应的按钮：AC1 要求它在位，而一个死按钮比没有按钮更糟。
  Future<void> _onShare(BuildContext context, WidgetRef ref, AppLocalizations l10n,
      PlaceDetail p, Rect? origin) async {
    try {
      // 走既有 shareServiceProvider —— 它带 sharePositionOrigin，而 **iOS 上缺了这个参数
      // iPad 会崩、iPhone 会「点了没反应」**（bug 20260707 踩过）。别绕过它直接调 Share.share。
      await ref.read(shareServiceProvider)('${p.name}\n${p.addressText}',
          sharePositionOrigin: origin);
    } catch (_) {
      if (context.mounted) showAppToast(context, l10n.placeDetailShareFailed);
    }
  }

  /// AC5：复用既有五类选项举报抽屉，**文案一字不改**。
  void _onReport(BuildContext context, WidgetRef ref) {
    final l10n = AppLocalizations.of(context);
    openReportSheet(
      context,
      ref,
      submit: (reason) =>
          ref.read(placeRepositoryProvider).reportPlace(token, reason.wire),
      onReported: () {
        // ⚠️ 与内容举报**不同**：内容举报成功后会把那条内容从本人视野移除（cm-6 §6.1），
        // 而场所是共享的地点条目 —— 举报它不代表它对你消失。所以这里**不 pop、不隐藏**，
        // 只安静地留在页面上（抽屉自己已经给了成功态）。
        if (context.mounted) showAppToast(context, l10n.placeDetailReported);
      },
    );
  }
}

/// 照片横滑流（AC1）。
///
/// ⚠️ **暂不可点**：点开看大图归 Story 1.6（那条把既有私有灯箱抽成公共组件）。
/// 现在挂一个空手势比不挂更糟 —— 用户会以为点了没反应。
class _PhotoStrip extends StatelessWidget {
  const _PhotoStrip({required this.urls});

  final List<String> urls;

  static const double _height = 200;

  @override
  Widget build(BuildContext context) {
    if (urls.isEmpty) {
      return Container(
        height: _height,
        color: AppColors.cream2,
        alignment: Alignment.center,
        child: const Icon(Icons.photo_outlined, size: 36, color: AppColors.textTertiary),
      );
    }
    return SizedBox(
      height: _height,
      child: ListView.separated(
        scrollDirection: Axis.horizontal,
        padding: const EdgeInsets.symmetric(horizontal: AppSpacing.lg),
        itemCount: urls.length,
        separatorBuilder: (_, _) => const SizedBox(width: AppSpacing.sm),
        itemBuilder: (context, i) => ClipRRect(
          borderRadius: BorderRadius.circular(12),
          child: AppImage.widget(
            urls[i],
            width: 280,
            height: _height,
            errorBuilder: (_, _, _) => Container(
              width: 280,
              color: AppColors.cream2,
              alignment: Alignment.center,
              child: const Icon(Icons.broken_image_outlined,
                  color: AppColors.textTertiary),
            ),
          ),
        ),
      ),
    );
  }
}

/// 文字地址 +「复制」（AC1/AC2）。
class _AddressRow extends StatelessWidget {
  const _AddressRow({required this.address, required this.onCopy});

  final String address;
  final VoidCallback onCopy;

  @override
  Widget build(BuildContext context) {
    final l10n = AppLocalizations.of(context);
    return Container(
      padding: const EdgeInsets.fromLTRB(AppSpacing.md, AppSpacing.sm, AppSpacing.xs,
          AppSpacing.sm),
      decoration: BoxDecoration(
        color: AppColors.cream2,
        borderRadius: BorderRadius.circular(10),
      ),
      child: Row(
        children: [
          Expanded(child: Text(address, style: AppTypography.caption)),
          TextButton(
            key: const ValueKey('placeDetailCopyAddress'),
            onPressed: onCopy,
            style: TextButton.styleFrom(
              minimumSize: const Size(44, 44),
              foregroundColor: AppColors.mint,
            ),
            child: Text(l10n.placeDetailCopyAddress,
                style: AppTypography.caption
                    .copyWith(color: AppColors.mint, fontWeight: FontWeight.w700)),
          ),
        ],
      ),
    );
  }
}

/// 标记人行（AC4）。已注销 → 本地化「已注销用户」+ 默认头像且**不可点**（NFR-8）。
class _MarkerRow extends StatelessWidget {
  const _MarkerRow({required this.marker, required this.onTap});

  final PlaceMarker marker;
  final VoidCallback? onTap;

  @override
  Widget build(BuildContext context) {
    final l10n = AppLocalizations.of(context);
    final name = marker.deleted ? l10n.feedDeletedUser : (marker.nickname ?? '');
    return InkWell(
      key: const ValueKey('placeDetailMarker'),
      onTap: onTap,
      child: Padding(
        // 竖向 10 + 头像 26 ≈ 46 > 44（UX-DR16）。
        padding: const EdgeInsets.symmetric(vertical: 10),
        child: Row(
          children: [
            LetterAvatar(
                name: name, url: marker.avatarUrl, deleted: marker.deleted, size: 26),
            const SizedBox(width: AppSpacing.sm),
            Expanded(
              child: Text(l10n.placeDetailMarkedBy(name), style: AppTypography.caption),
            ),
            if (onTap != null)
              const Icon(Icons.chevron_right_rounded,
                  size: 18, color: AppColors.textTertiary),
          ],
        ),
      ),
    );
  }
}

class _ShareButton extends StatelessWidget {
  const _ShareButton({required this.onTap});

  /// 回调带上按钮自身的屏幕矩形 —— iOS 的系统分享面板需要它定位（见 `shareServiceProvider`）。
  final void Function(Rect? origin) onTap;

  @override
  Widget build(BuildContext context) {
    final l10n = AppLocalizations.of(context);
    return Align(
      alignment: Alignment.centerLeft,
      child: Builder(builder: (buttonContext) => OutlinedButton.icon(
        key: const ValueKey('placeDetailShare'),
        onPressed: () => onTap(_originOf(buttonContext)),
        style: OutlinedButton.styleFrom(
          minimumSize: const Size(44, 44),
          foregroundColor: AppColors.mint,
          side: const BorderSide(color: AppColors.lineViolet),
        ),
        icon: const Icon(Icons.share_outlined, size: 16),
        label: Text(l10n.placeDetailShare),
      )),
    );
  }

  /// 按钮在屏幕上的矩形（拿不到就 null —— 安卓不需要它）。
  static Rect? _originOf(BuildContext context) {
    final box = context.findRenderObject();
    if (box is! RenderBox || !box.hasSize) return null;
    return box.localToGlobal(Offset.zero) & box.size;
  }
}

/// 📷 · 💬 · 👍 · 👎 计数行。
///
/// ⚠️ 评论数与两个态度计数在 Story 1.7/1.8 之前恒为 0 —— 与列表页同一处理，
/// **不做「为 0 就隐藏」**（隐藏的话 1.8 上线前没人能验证它们渲染对不对）。
class _CountsRow extends StatelessWidget {
  const _CountsRow({required this.detail});

  final PlaceDetail detail;

  @override
  Widget build(BuildContext context) {
    return Row(
      children: [
        _CountItem(icon: Icons.photo_library_outlined, value: detail.photoUrls.length),
        _CountItem(
            icon: Icons.chat_bubble_outline_rounded, value: detail.commentCount),
        _CountItem(icon: Icons.thumb_up_outlined, value: detail.recommendCount),
        _CountItem(icon: Icons.thumb_down_outlined, value: detail.notRecommendCount),
      ],
    );
  }
}

class _CountItem extends StatelessWidget {
  const _CountItem({required this.icon, required this.value});

  final IconData icon;
  final int value;

  @override
  Widget build(BuildContext context) {
    return Padding(
      padding: const EdgeInsets.only(right: AppSpacing.md),
      child: Row(
        children: [
          Icon(icon, size: 14, color: AppColors.textTertiary),
          const SizedBox(width: AppSpacing.xxs),
          Text('$value', style: AppTypography.caption),
        ],
      ),
    );
  }
}

class _TagChip extends StatelessWidget {
  const _TagChip({required this.label});

  final String label;

  @override
  Widget build(BuildContext context) {
    return Container(
      padding: const EdgeInsets.symmetric(horizontal: AppSpacing.sm, vertical: 4),
      decoration: BoxDecoration(
        border: Border.all(color: AppColors.lineViolet),
        borderRadius: BorderRadius.circular(999),
      ),
      child: Text(label,
          style: AppTypography.micro.copyWith(color: AppColors.mint700)),
    );
  }
}
