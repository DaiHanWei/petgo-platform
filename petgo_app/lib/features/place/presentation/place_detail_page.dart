import 'package:dio/dio.dart';
import 'package:flutter/material.dart';
import 'package:flutter/services.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:url_launcher/url_launcher.dart';

import '../../../core/theme/colors.dart';
import '../../../core/theme/spacing.dart';
import '../../../core/theme/typography.dart';
import '../../../l10n/app_localizations.dart';
import '../../../shared/utils/image_processor.dart';
import '../../../shared/widgets/app_image.dart';
import '../../../shared/widgets/app_toast.dart';
import '../../../shared/widgets/confirm_sheet.dart';
import '../../../shared/widgets/empty_state.dart';
import '../../../shared/widgets/letter_avatar.dart';
import '../../../shared/widgets/photo_lightbox.dart';
import '../../../core/media/media_scope.dart';
import '../../../core/router/route_intent.dart';
import '../../user_profile/presentation/public_profile_page.dart';
import '../../auth/domain/auth_guard.dart';
import '../../content/presentation/report_sheet.dart';
import '../../media/domain/media_upload_use_case.dart';
import '../../profile/domain/card_link.dart';
import '../../profile/domain/share_service.dart';
import '../data/place_repository.dart';
import '../domain/place_comment.dart';
import '../domain/place_detail.dart';
import 'place_comment_composer.dart';
import 'place_comments_controller.dart';
import 'place_comment_section.dart';
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
///   <li>**点照片看大图**已接上（Story 1.6）：走公共 `PhotoLightbox`（从内容详情页原样抽出，行为一字未改）；</li>
///   <li>**评论区 + 二元态度**已接上（Story 1.7）：一级 only，态度在输入的展开态里；</li>
///   <li>**分享**（Story 1.10）分享的是 **H5 场所页链接**（`/place/{不可枚举 token}`），见 `_onShare`。</li>
/// </ul>
///
/// <h2>⚠️ 标记人点击是有意的临时方案（AC4）</h2>
/// 本 story 内点标记人走**现有迷你主页卡**。写成「进公开主页」会让本 story 依赖 Epic 2
/// （主页还不存在）—— Epic 2 的 Story 2-1 会统一收口三处入口，那时本页一并改掉。
/// **别在这里提前接主页。**
/// 「正在上传补充照片」（按场所 token 分族）。
///
/// 🔴 没有它的话，20 秒的上传过程中用户再点一次「+」就会起第二次并发补充 ——
/// 两次都过了客户端的剩余张数判断，服务端那边一个成功一个 422，而失败的那几张
/// 已经躺在公开桶里成了孤儿（code-review 2026-09-15）。
/// ⚠️ Riverpod 3 的默认导出里**没有 `StateProvider`**（它退到了 legacy 命名空间）——
/// 一个只装 bool 的 `Notifier` 就够，也省得把弃用的 API 重新引进来。
class _PhotoUploading extends Notifier<bool> {
  @override
  bool build() => false;

  // ignore: use_setters_to_change_properties
  void set(bool value) => state = value;
}

final _photoUploadingProvider =
    NotifierProvider.family<_PhotoUploading, bool, String>(
  (_) => _PhotoUploading(),
  isAutoDispose: true,
);

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
      // 🔴 输入条只在**场所真的存在**时出现（Story 1.7）：
      // 404 空态下挂一个输入框，用户打完字一发就是另一个 404。
      composer: async.hasValue ? PlaceCommentComposer(token: token) : null,
    );
  }

  Widget _scaffold(AppLocalizations l10n, Widget body,
          {VoidCallback? onReport, Widget? composer}) =>
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
        // 吸底输入条（UI 稿 A4）。⚠️ 用 bottomNavigationBar 而不是 Stack：
        // 前者会自动把 body 的底部内边距让出来，评论区最后一条不会被输入条盖住。
        bottomNavigationBar: composer,
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
        _PhotoStrip(
          photos: p.photos,
          slotsRemaining: p.photoSlotsRemaining,
          // Story 1.9：任何登录用户都能补图（场所是共享条目，不是标记人的私产）。
          onContribute: () => _onContributePhotos(context, ref, p),
          onDeletePhoto: (photo) => _onDeletePhoto(context, ref, photo),
          uploading: ref.watch(_photoUploadingProvider(token)),
        ),
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
                // 点标记人 → 完整主页（UI 稿 A4 的「点标记人 → 跳 C1」）。
                // ⚠️ Story 1.5 落地时主页还不存在，先接的迷你卡；Story 2.1 统一收口时改到这里。
                onTap: p.markedBy.tappable
                    ? () => openUserProfile(context, ref, p.markedBy.userId)
                    : null,
              ),
              const SizedBox(height: AppSpacing.md),
              _ShareButton(onTap: (origin) => _onShare(context, ref, l10n, p, origin)),
              const SizedBox(height: AppSpacing.lg),
              // 计数行：照片数 / 评论数 / 👍 / 👎（两个态度计数由 Story 1.8 接真值）。
              _CountsRow(detail: p),
              const SizedBox(height: AppSpacing.lg),
              const Divider(height: 1, thickness: 1, color: AppColors.line2),
              const SizedBox(height: AppSpacing.md),
              // 评论区（Story 1.7）。🔒 游客可读，发言时才走登录引导。
              PlaceCommentSection(token: token),
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

  /// 分享（Story 1.10 · AC1）：唤起系统面板，内容是 **H5 场所页链接**。
  ///
  /// 🔴 链接里是**不可枚举 token**（`placeShareUrl`）—— 不是场所名、不是自增 id（AC2）。
  ///
  /// ⚠️ **不接分享奖励**（AC8）：FR-96 的奖励渠道只有年龄卡 / Tailsonality 卡 / 护照卡三个，
  /// 场所不在其中。所以这里**不调** `shareRewardProvider` 之类的东西 ——
  /// 加进去就是给一条没人批准过的渠道发币。
  ///
  /// 文案 = 名称 + 链接：只发链接的话，IM 预览卡还没抓出来的那一两秒里，
  /// 收到的人看到的是一串不知道是什么的 URL。
  Future<void> _onShare(BuildContext context, WidgetRef ref, AppLocalizations l10n,
      PlaceDetail p, Rect? origin) async {
    try {
      // 走既有 shareServiceProvider —— 它带 sharePositionOrigin，而 **iOS 上缺了这个参数
      // iPad 会崩、iPhone 会「点了没反应」**（bug 20260707 踩过）。别绕过它直接调 Share.share。
      await ref.read(shareServiceProvider)('${p.name}\n${placeShareUrl(p.token)}',
          sharePositionOrigin: origin);
    } catch (_) {
      if (context.mounted) showAppToast(context, l10n.placeDetailShareFailed);
    }
  }

  /// 补充照片（Story 1.9 · AC1）。
  ///
  /// 🔒 **游客走登录引导**：看照片不需要登录，补图需要（后端要 JWT，而且照片要标注上传者）。
  ///
  /// 🔴 走**既有上传链路**（`MediaUploadUseCase` + 公开桶）——
  /// 不新写一条上传路径：那条链路已经带着「仅图片、无视频」（F4）与客户端 EXIF 剥离，
  /// 另起一条就等于把这两样重新实现一遍。
  Future<void> _onContributePhotos(
      BuildContext context, WidgetRef ref, PlaceDetail p) async {
    requireLogin(
      ref,
      context,
      // 与列表页「标记场所」同一处理：用 onResume 的命令式回调而不是声明式 location ——
      // 详情页是 push 进来的，`go` 会把整个栈换掉。
      pendingAction: RouteIntent(onResume: () {
        if (context.mounted) _pickAndUploadPhotos(context, ref, p);
      }),
      onAllowed: () => _pickAndUploadPhotos(context, ref, p),
    );
  }

  Future<void> _pickAndUploadPhotos(
      BuildContext context, WidgetRef ref, PlaceDetail p) async {
    final l10n = AppLocalizations.of(context);
    // 🔴 **第一个 await 之前**把后面要用的全部取好（batch-b1 复审）：上传可能要 20 秒，
    // 用户中途返回后 WidgetRef 已随页面销毁，再 ref.read 会抛 —— 被下面的 catch 吞掉，
    // 于是 contributePhotos 永远没调，已传上公开桶的照片成了孤儿。
    // 走 ProviderContainer：它比页面活得久，离开页面后照样能把这批照片提交出去。
    final container = ProviderScope.containerOf(context, listen: false);
    final useCase = container.read(mediaUploadUseCaseProvider);
    final repo = container.read(placeRepositoryProvider);
    final uploadingProvider = _photoUploadingProvider(token);
    // 🔴 上传中再点一次 → 直接返回（第二次并发补充会让两批各自算剩余张数，
    // 服务端那边一个成功一个 422，失败的那几张已经在公开桶里成了孤儿）。
    if (container.read(uploadingProvider)) return;

    // 🔴 **按剩余张数选图**，不是固定 9（code-review 2026-09-15）：
    // 一个已有 7 张照片的场所里选 9 张，会把 9 张全传上公开桶、然后整批 422 ——
    // 用户只看到一句"上传失败"，而那 9 个对象已经在桶里了。
    // 剩余张数以服务端下发为准（batch-b1 复审：本地看不到别人审核中的照片）。
    final slots = p.photoSlotsRemaining;
    if (slots <= 0) return;

    // 上传期间持有一个订阅：_photoUploadingProvider 是 autoDispose，页面一走它就会被回收，
    // finally 里再 set(false) 会抛 UnmountedRefException。持有订阅 = 上传期间它一直活着
    // （用户中途回到本页也能看到"上传中"）。
    final keepAlive = container.listen(uploadingProvider, (_, _) {});
    final uploading = container.read(uploadingProvider.notifier);
    uploading.set(true);
    try {
      final picked = await useCase.pickMultiAndProcess(limit: slots, context: context);
      if (picked.isEmpty) return;

      final urls = <String>[];
      Object? failure;
      for (final bytes in picked) {
        try {
          final result =
              await useCase.uploadBytes(scope: MediaScope.public, bytes: bytes);
          final url = result.publicUrl;
          if (url == null || url.isEmpty) {
            throw StateError('公开桶上传没有回 publicUrl');
          }
          urls.add(url);
        } catch (e) {
          // 🔴 一张失败不丢已成功的那几张（同标记表单的既定处理）。
          failure = e;
        }
      }
      if (urls.isNotEmpty) {
        await repo.contributePhotos(token, urls);
        invalidatePlaceDetailIn(container, token);
      }
      if (!context.mounted) return;
      if (failure != null) {
        showAppToast(
            context,
            failure is ImageProcessingException
                ? l10n.placeMarkPhotoTooLarge
                : l10n.placeMarkPhotoUploadFailed);
      } else if (urls.isNotEmpty) {
        // ⚠️ 提示「审核中」而不是「已发布」：补充的照片先发后审，
        // 此刻只有他自己看得见 —— 说成"已发布"他会去问别人为什么看不到。
        showAppToast(context, l10n.placePhotoSubmitted);
      }
    } on ImageProcessingException {
      if (context.mounted) showAppToast(context, l10n.placeMarkPhotoTooLarge);
    } catch (_) {
      if (context.mounted) showAppToast(context, l10n.placeMarkPhotoUploadFailed);
    } finally {
      uploading.set(false);
      keepAlive.close();
    }
  }

  /// 删除**自己传的**那张照片。
  ///
  /// 🔒 「是不是本人」由服务端校验 —— `mine` 只决定画不画这个入口。
  /// ⚠️ 服务端还会挡「删到零张」（场所照片是必填的，而场所不可编辑）——
  /// 那条走通用失败提示，客户端不重复实现一遍判断（两处判断迟早会不一致）。
  Future<void> _onDeletePhoto(
      BuildContext context, WidgetRef ref, PlacePhoto photo) async {
    final l10n = AppLocalizations.of(context);
    final ok = await showConfirmSheet(
      context,
      title: l10n.placePhotoDeleteTitle,
      confirmLabel: l10n.placeCommentDeleteConfirm,
      cancelLabel: l10n.commonCancel,
      icon: Icons.delete_outline_rounded,
      danger: true,
      confirmKey: const ValueKey('placePhotoDeleteConfirm'),
    );
    if (!ok) return;
    try {
      await ref.read(placeRepositoryProvider).deletePhoto(photo.id);
      invalidatePlaceDetail(ref, token);
      if (context.mounted) showAppToast(context, l10n.placePhotoDeleted);
    } catch (_) {
      if (context.mounted) showAppToast(context, l10n.placePhotoDeleteFailed);
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

/// 照片横滑流（AC1）+ 点开全屏灯箱（Story 1.6 · AC2）。
///
/// <h2>⚠️ 服务端已经决定了尺寸，客户端这里不能再缩</h2>
/// 后端 `PlaceDetailResponse` 给每条 URL 都拼好了
/// `x-oss-process=image/resize,w_1080/format,jpg` —— 那个 `format,jpg` 重编码**就是
/// E4 的服务端 EXIF 兜底**（场所照片进的是公开桶，改过的客户端能绕过客户端剥离）。
/// 所以：
/// <ul>
///   <li>**不传 `thumbWidth`**：`AppImage.ossResized` 对已带 `x-oss-process` 的 URL
///       原样返回（app_image.dart），传了只是个看起来有用的死参数；</li>
///   <li>**灯箱与横滑流拿的是同一条 URL**，也就是 1080 px 那一版 ——
///       这是当前能拿到的最大安全尺寸，**不是真正的原图**。要更清晰得改后端出口尺寸，
///       而不是在前端加参数（真·原图不能直接外发：那条路径没有 `format,jpg`，会带着 GPS）。</li>
/// </ul>
class _PhotoStrip extends StatelessWidget {
  const _PhotoStrip({
    required this.photos,
    required this.slotsRemaining,
    required this.onContribute,
    required this.onDeletePhoto,
    required this.uploading,
  });

  final List<PlacePhoto> photos;

  /// 「补充照片」（Story 1.9 · AC1）。
  final VoidCallback onContribute;

  /// 删除**自己传的**那张。
  final void Function(PlacePhoto photo) onDeletePhoto;

  /// 正在上传 —— 「+」格换成转圈且点不动。
  final bool uploading;

  /// 服务端算好的剩余名额（见 [PlaceDetail.photoSlotsRemaining]）。
  final int slotsRemaining;

  static const double _height = 200;

  /// 横滑流每张图的宽度（逻辑像素）。
  static const double _itemWidth = 280;

  @override
  Widget build(BuildContext context) {
    final urls = photos.map((p) => p.url).toList(growable: false);
    // 🔴 按服务端的剩余名额，不按 photos.length（看不到别人审核中的照片）。
    final canAdd = slotsRemaining > 0;
    if (photos.isEmpty) {
      // 空态也要给补图入口 —— 一个没有照片的场所正是最需要别人补图的那个。
      // （名额被别人审核中的照片占满时除外：点了只会收到一个 422。）
      return GestureDetector(
        onTap: uploading || !canAdd ? null : onContribute,
        child: Container(
          height: _height,
          color: AppColors.cream2,
          alignment: Alignment.center,
          child: uploading
              ? const CircularProgressIndicator()
              : const Icon(Icons.add_a_photo_outlined,
                  size: 36, color: AppColors.textTertiary),
        ),
      );
    }
    return SizedBox(
      height: _height,
      child: ListView.separated(
        scrollDirection: Axis.horizontal,
        padding: const EdgeInsets.symmetric(horizontal: AppSpacing.lg),
        // 末尾多一格「+」：满 9 张就不给了（点了只会收到一个 422）。
        itemCount: photos.length + (canAdd ? 1 : 0),
        separatorBuilder: (_, _) => const SizedBox(width: AppSpacing.sm),
        itemBuilder: (context, i) {
          if (i >= photos.length) {
            return _AddPhotoTile(
              onTap: onContribute,
              height: _height,
              uploading: uploading,
            );
          }
          return _PhotoTile(
            photo: photos[i],
            width: _itemWidth,
            height: _height,
            onTap: () => openPhotoLightbox(context, urls: urls, initialIndex: i),
            onDelete: photos[i].mine ? () => onDeletePhoto(photos[i]) : null,
          );
        },
      ),
    );
  }
}

/// 一张照片 + **上传者标注**（AC2）。
///
/// 🔴 标注浮在图上而不是另起一行：横滑流一屏只放得下一张多一点，
/// 另起一行会让每张图矮一截、还要用户把标注和图对上号。
class _PhotoTile extends StatelessWidget {
  const _PhotoTile({
    required this.photo,
    required this.width,
    required this.height,
    required this.onTap,
    required this.onDelete,
  });

  final PlacePhoto photo;
  final double width;
  final double height;
  final VoidCallback onTap;

  /// 自己传的那张才有删除入口（null = 不画）。
  final VoidCallback? onDelete;

  @override
  Widget build(BuildContext context) {
    final l10n = AppLocalizations.of(context);
    final name = photo.uploaderDeleted
        ? l10n.feedDeletedUser
        : (photo.uploaderNickname ?? '');
    return GestureDetector(
      onTap: onTap,
      child: ClipRRect(
        borderRadius: BorderRadius.circular(12),
        child: Stack(
          children: [
            AppImage.widget(
              photo.url,
              width: width,
              height: height,
              errorBuilder: (_, _, _) => Container(
                width: width,
                color: AppColors.cream2,
                alignment: Alignment.center,
                child: const Icon(Icons.broken_image_outlined,
                    color: AppColors.textTertiary),
              ),
            ),
            // 非 VISIBLE 的行只会下发给上传者本人。不标的话他会以为照片没传上去。
            // 🔴 **要区分"审核中"和"未通过"**（code-review 2026-09-15）：
            // 被判死的照片是终态，一直标着"审核中"等于让他永远等一个不会来的结果。
            if (photo.moderation.onlyVisibleToMe)
              Positioned(
                top: AppSpacing.xs,
                left: AppSpacing.xs,
                child: _PhotoChip(
                  text: photo.moderation == PlaceCommentModeration.underReview
                      ? l10n.placePhotoUnderReview
                      : l10n.placePhotoRejected,
                ),
              ),
            if (onDelete != null)
              Positioned(
                top: 0,
                right: 0,
                child: GestureDetector(
                  key: ValueKey('placePhotoDelete-${photo.id}'),
                  onTap: onDelete,
                  // 44×44 热区（UX-DR16）：图标只有 16，靠 padding 撑开。
                  child: const Padding(
                    padding: EdgeInsets.all(14),
                    child: Icon(Icons.close_rounded, size: 16, color: Colors.white),
                  ),
                ),
              ),
            if (name.isNotEmpty)
              Positioned(
                left: AppSpacing.xs,
                right: AppSpacing.xs,
                bottom: AppSpacing.xs,
                child: _PhotoChip(text: l10n.placePhotoBy(name)),
              ),
          ],
        ),
      ),
    );
  }
}

/// 浮在照片上的小标签（半透明黑底 —— 图片底色不可控，纯白字在浅色照片上看不见）。
class _PhotoChip extends StatelessWidget {
  const _PhotoChip({required this.text});

  final String text;

  @override
  Widget build(BuildContext context) {
    return Container(
      padding: const EdgeInsets.symmetric(horizontal: AppSpacing.sm, vertical: 3),
      decoration: BoxDecoration(
        color: Colors.black54,
        borderRadius: BorderRadius.circular(999),
      ),
      child: Text(text,
          maxLines: 1,
          overflow: TextOverflow.ellipsis,
          style: AppTypography.micro.copyWith(color: Colors.white)),
    );
  }
}

/// 横滑流末尾的「补充照片」格（AC1）。
class _AddPhotoTile extends StatelessWidget {
  const _AddPhotoTile({
    required this.onTap,
    required this.height,
    required this.uploading,
  });

  final VoidCallback onTap;
  final double height;
  final bool uploading;

  @override
  Widget build(BuildContext context) {
    final l10n = AppLocalizations.of(context);
    return GestureDetector(
      key: const ValueKey('placeAddPhoto'),
      // 上传中点不动 —— 第二次并发补充会把两批都算错剩余张数。
      onTap: uploading ? null : onTap,
      child: Container(
        width: 120,
        height: height,
        decoration: BoxDecoration(
          color: AppColors.cream2,
          borderRadius: BorderRadius.circular(12),
          border: Border.all(color: AppColors.lineViolet),
        ),
        child: Column(
          mainAxisAlignment: MainAxisAlignment.center,
          children: [
            if (uploading)
              const SizedBox(
                  width: 26, height: 26, child: CircularProgressIndicator(strokeWidth: 2))
            else
              const Icon(Icons.add_a_photo_outlined, size: 26, color: AppColors.mint),
            const SizedBox(height: AppSpacing.xs),
            Padding(
              padding: const EdgeInsets.symmetric(horizontal: AppSpacing.sm),
              child: Text(l10n.placePhotoAdd,
                  textAlign: TextAlign.center,
                  style: AppTypography.micro.copyWith(color: AppColors.mint700)),
            ),
          ],
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
/// 三个数字的口径**不一样**，这是有意的（见后端说明）：
/// 评论数是 **viewer 维度**的（拉黑过滤让它因人而异，且必须与评论区列出来的条数一致），
/// 而 👍/👎 是**平台口径**（"大家觉得这地方行不行"，与谁在看无关）。
///
/// ⚠️ **不做「为 0 就隐藏」** —— 与列表页同一处理：全 0 是新场所的正常状态。
class _CountsRow extends StatelessWidget {
  const _CountsRow({required this.detail});

  final PlaceDetail detail;

  @override
  Widget build(BuildContext context) {
    return Row(
      children: [
        _CountItem(icon: Icons.photo_library_outlined, value: detail.photos.length),
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
