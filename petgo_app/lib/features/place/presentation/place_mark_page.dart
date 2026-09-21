import 'package:dio/dio.dart';
import 'package:flutter/material.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';

import '../../../core/media/media_scope.dart';
import '../../../core/network/problem_detail.dart';
import '../../../core/theme/colors.dart';
import '../../../core/theme/spacing.dart';
import '../../../core/theme/typography.dart';
import '../../../l10n/app_localizations.dart';
import '../../../shared/utils/image_processor.dart';
import '../../../shared/widgets/app_image.dart';
import '../../../shared/widgets/app_toast.dart';
import '../../media/domain/media_upload_use_case.dart';
import '../data/location_service.dart';
import '../data/place_repository.dart';
import '../domain/place_form.dart';
import '../domain/place_summary.dart';
import 'place_labels.dart';
import 'place_location_controller.dart';
import 'place_map_picker_sheet.dart';

/// 内联错误的作用域。见 [_PlaceMarkPageState._touched]。
enum _Field { name, type, tags, address, location, photos }

/// 标记场所表单（V1.3.0 batch-b1 Story 1.3 · UI 稿 A5）。
///
/// <h2>🔴 三条本页特有的硬约束</h2>
/// <ol>
///   <li><b>类型 7 个、标签 6 个，全量渲染</b>（AC1）。UI 稿 A5 里只画了几个是**示意省略**
///       （UX-DR4），不是真实清单。少给一个，用户就永远标不出那一类场所。</li>
///   <li><b>「保存」在必填未满时是灰的</b>（AC2）—— 不允许"点了没反应又不说少了什么"。
///       判断口径在 [PlaceFormDraft.canSubmit]（纯逻辑，L0 有测试）。</li>
///   <li><b>表单一渲染就告知「提交后不可修改」</b>（AC4）：本版用户不可编辑场所
///       （2026-09-15 拍板），纠错只能走后台。这句话必须在用户开始填之前就看到，
///       而不是提交时才弹。</li>
/// </ol>
///
/// <p>位置来源（Story 1.3 AC3 + Story 1.4 AC3）：默认取**当前定位**；点「在地图上选」
/// 打开选点弹层（`PlaceMapPickerSheet`），针默认落当前位置 ——
/// **不动它直接确认 = 与 1.3 的行为等价**。没有定位权限时弹层仍可用（落雅加达市中心），
/// 因为「人不在现场也能标」正是 2026-08-28 把「必须现场标记」改掉的原因。
class PlaceMarkPage extends ConsumerStatefulWidget {
  const PlaceMarkPage({super.key});

  static const String routePath = '/places/new';

  @override
  ConsumerState<PlaceMarkPage> createState() => _PlaceMarkPageState();
}

class _PlaceMarkPageState extends ConsumerState<PlaceMarkPage> {
  PlaceFormDraft _draft = const PlaceFormDraft();
  bool _submitting = false;
  bool _uploading = false;

  /// 已经被用户碰过的字段 —— 内联错误**只对碰过的字段显示**（AC7）。
  ///
  /// 🔴 <b>不能只在「点了保存」之后才显示</b>：保存按钮在必填未满时是灰的（AC2），
  /// 那个分支根本走不到，内联错误就成了永远不显示的死代码（code-review 2026-09-15 抓到）。
  /// 按「碰过且不合规」判：用户在名称里打了字又删空 → 立刻看到「请填名称」，
  /// 而刚进页面时一片干净、不会被提前指责一遍。
  final Set<_Field> _touched = <_Field>{};

  bool _errorFor(_Field f, bool ok) => _touched.contains(f) && !ok;

  void _touch(_Field f) => _touched.add(f);

  /// 本次提交的幂等键。
  ///
  /// 🔴 **整页只生成一次**（不是每次点保存都换一个）：用户点了保存、请求发出去但响应丢了、
  /// 他再点一次 —— 只有复用同一个 key，服务端才能认出这是同一次提交而不是两个场所。
  /// 而场所是**不能删除也不能编辑**的，重复了只能等运营去后台合并。
  late final String _idempotencyKey =
      'place-${DateTime.now().microsecondsSinceEpoch}-${identityHashCode(this)}';

  final _nameController = TextEditingController();
  final _addressController = TextEditingController();
  final _descriptionController = TextEditingController();

  @override
  void dispose() {
    _nameController.dispose();
    _addressController.dispose();
    _descriptionController.dispose();
    super.dispose();
  }

  @override
  Widget build(BuildContext context) {
    final l10n = AppLocalizations.of(context);
    // 定位态：已授权就把坐标灌进草稿（AC3）。
    final locationAsync = ref.watch(placeLocationProvider);
    final coords = locationAsync.value?.coordinates;
    if (coords != null && !_draft.hasLocation) {
      // build 里不能直接改 state；下一帧灌入。
      WidgetsBinding.instance.addPostFrameCallback((_) {
        if (!mounted) return;
        setState(() => _draft =
            _draft.copyWith(latitude: coords.latitude, longitude: coords.longitude));
      });
    }

    return Scaffold(
      backgroundColor: AppColors.cream,
      appBar: AppBar(
        backgroundColor: AppColors.cream,
        scrolledUnderElevation: 0,
        title: Text(l10n.placeMarkTitle, style: AppTypography.title),
      ),
      body: SafeArea(
        child: Column(
          children: [
            Expanded(
              child: ListView(
                padding: const EdgeInsets.all(AppSpacing.lg),
                children: [
                  // AC4：前置告知，放在最上面、在所有输入之前。
                  _ImmutableNotice(text: l10n.placeMarkImmutableNotice),
                  const SizedBox(height: AppSpacing.lg),
                  _field(
                    label: l10n.placeMarkNameLabel,
                    required: true,
                    error: _errorFor(_Field.name, _draft.nameOk) ? l10n.placeMarkNameError : null,
                    child: TextField(
                      controller: _nameController,
                      maxLength: PlaceFormDraft.nameMaxLength,
                      decoration: InputDecoration(hintText: l10n.placeMarkNameHint),
                      onChanged: (v) => setState(() {
                        _touch(_Field.name);
                        _draft = _draft.copyWith(name: v);
                      }),
                    ),
                  ),
                  _field(
                    label: l10n.placeMarkTypeLabel,
                    required: true,
                    error: _errorFor(_Field.type, _draft.typeOk) ? l10n.placeMarkTypeError : null,
                    child: Wrap(
                      spacing: AppSpacing.sm,
                      runSpacing: AppSpacing.sm,
                      children: [
                        // 🔴 全部 7 类，一个不少。
                        for (final t in PlaceType.values)
                          _ChoiceChip(
                            label: t.label(l10n),
                            selected: _draft.type == t,
                            onTap: () => setState(() {
                              _touch(_Field.type);
                              _draft = _draft.copyWith(type: t);
                            }),
                          ),
                      ],
                    ),
                  ),
                  _field(
                    label: l10n.placeMarkTagsLabel,
                    required: true,
                    error: _errorFor(_Field.tags, _draft.tagsOk) ? l10n.placeMarkTagsError : null,
                    child: Wrap(
                      spacing: AppSpacing.sm,
                      runSpacing: AppSpacing.sm,
                      children: [
                        // 🔴 全部 6 个，一个不少。
                        for (final t in PlaceTag.values)
                          _ChoiceChip(
                            label: t.label(l10n),
                            selected: _draft.tags.contains(t),
                            onTap: () => setState(() {
                              _touch(_Field.tags);
                              _draft = _draft.toggleTag(t);
                            }),
                          ),
                      ],
                    ),
                  ),
                  _field(
                    label: l10n.placeMarkAddressLabel,
                    required: true,
                    error: _errorFor(_Field.address, _draft.addressOk)
                        ? l10n.placeMarkAddressError
                        : null,
                    child: TextField(
                      controller: _addressController,
                      maxLength: PlaceFormDraft.addressMaxLength,
                      maxLines: 2,
                      minLines: 1,
                      decoration: InputDecoration(hintText: l10n.placeMarkAddressHint),
                      onChanged: (v) => setState(() {
                        _touch(_Field.address);
                        _draft = _draft.copyWith(addressText: v);
                      }),
                    ),
                  ),
                  _LocationRow(
                    hasLocation: _draft.hasLocation,
                    // AC3：未授权定位时**明确提示需要位置**，不静默失败。
                    needsPermission:
                        locationAsync.value?.needsPermissionBanner ?? true,
                    locating: locationAsync.isLoading,
                    onEnable: _onEnableLocation,
                    onRetry: _onRetryLocate,
                    // Story 1.4：地图选点。**替换**「只能取当前定位」那条路径，
                    // 但默认落点仍是当前位置 —— 不动针直接确认 = 与 1.3 等价。
                    onPickOnMap: _onPickOnMap,
                  ),
                  if (_errorFor(_Field.location, _draft.hasLocation))
                    _ErrorText(l10n.placeMarkLocationError),
                  const SizedBox(height: AppSpacing.lg),
                  _field(
                    label: l10n.placeMarkPhotosLabel,
                    required: true,
                    error: _errorFor(_Field.photos, _draft.photosOk)
                        ? l10n.placeMarkPhotosError
                        : null,
                    child: _PhotoGrid(
                      urls: _draft.photoUrls,
                      uploading: _uploading,
                      onAdd: _onAddPhotos,
                      onRemove: (i) => setState(() {
                        _touch(_Field.photos);
                        _draft = _draft.removePhotoAt(i);
                      }),
                    ),
                  ),
                  _field(
                    label: l10n.placeMarkDescriptionLabel,
                    required: false,
                    error: null,
                    child: TextField(
                      controller: _descriptionController,
                      maxLength: PlaceFormDraft.descriptionMaxLength,
                      maxLines: 4,
                      minLines: 2,
                      decoration: InputDecoration(hintText: l10n.placeMarkDescriptionHint),
                      onChanged: (v) =>
                          setState(() => _draft = _draft.copyWith(description: v)),
                    ),
                  ),
                ],
              ),
            ),
            _SubmitBar(
              // AC2：必填未满 → 灰色禁用。
              enabled: _draft.canSubmit && !_submitting && !_uploading,
              submitting: _submitting,
              label: l10n.placeMarkSubmit,
              onSubmit: _onSubmit,
            ),
          ],
        ),
      ),
    );
  }

  /// AC3：开启定位。与列表页同一条路径（控制器只在用户点按钮时才弹系统窗）。
  Future<void> _onEnableLocation() async {
    final controller = ref.read(placeLocationProvider.notifier);
    final current = ref.read(placeLocationProvider).value;
    if (current?.mustGoToSettings ?? false) {
      await controller.openSettings();
      return;
    }
    await controller.requestPermission();
    if (mounted) setState(() => _touch(_Field.location));
  }

  /// 🔴 **权限给了但定点没拿到**时的出路（code-review 2026-09-15）。
  ///
  /// 「已授权 + 坐标为 null」是**常态**（系统定位总开关关着、室内 5 秒内没定点），
  /// 而那时既没有「开启定位」按钮（权限已经有了）、保存又一直是灰的 ——
  /// 用户卡在一个没有任何可点的东西的页面上，退出重进也一样（provider 缓存着那个 null）。
  /// 所以这里给一个「重新定位」：invalidate 定位 provider 让它整条重跑一次。
  Future<void> _onRetryLocate() async {
    ref.invalidate(placeLocationProvider);
    setState(() => _touch(_Field.location));
  }

  /// 地图选点（Story 1.4 · AC3）。
  ///
  /// 打开时大头针落**当前位置**；没有定位（未授权 / 没定点）→ 落雅加达市中心（AC4：
  /// 不空白、不崩）。用户拖针或点地图选任意位置，「确认位置」回填坐标。
  ///
  /// 🔴 这条路径让「人不在现场也能标」成立 —— 而这正是 2026-08-28 把「必须现场标记」
  /// 改成地图选点的原因。所以**即便没有定位权限也要能进这个弹层**。
  Future<void> _onPickOnMap() async {
    DeviceCoordinates? current;
    if (_draft.hasLocation) {
      current =
          DeviceCoordinates(latitude: _draft.latitude!, longitude: _draft.longitude!);
    } else {
      // 🔴 **要 await 定位 future，不能只 read 当前值**（code-review 2026-09-15）：
      // 定位链路还在跑（最长到 5 秒 GPS 超时）时 `value` 是 null —— 一个已授权的用户
      // 早点了一下这个按钮，就会拿到雅加达兜底而不是他自己的位置。
      try {
        current = (await ref.read(placeLocationProvider.future)).coordinates;
      } catch (_) {
        // 定位链路失败不该挡住选点 —— 弹层本来就允许没有定位（AC4）。
      }
      if (!mounted) return;
    }
    final picked = await PlaceMapPickerSheet.open(context, current);
    if (!mounted || picked == null) return;
    setState(() {
      _touch(_Field.location);
      _draft =
          _draft.copyWith(latitude: picked.latitude, longitude: picked.longitude);
    });
  }

  /// 选图 + 上传。复用既有 `MediaUploadUseCase`（权限、压缩、EXIF 剥离、直传全在里面）。
  ///
  /// 🛡 只有**上传成功**的 URL 才进草稿 —— 本地选中但没传完的图不算「填好了」，
  /// 否则保存按钮会在图还没传完时就亮。
  Future<void> _onAddPhotos() async {
    final slots = _draft.remainingPhotoSlots;
    if (slots <= 0 || _uploading) return;
    final l10n = AppLocalizations.of(context);
    final useCase = ref.read(mediaUploadUseCaseProvider);

    setState(() => _uploading = true);
    try {
      final picked = await useCase.pickMultiAndProcess(limit: slots, context: context);
      final urls = <String>[];
      Object? failure;
      for (final bytes in picked) {
        try {
          final result =
              await useCase.uploadBytes(scope: MediaScope.public, bytes: bytes);
          final url = result.publicUrl;
          // 公开桶票据一定带 publicUrl；拿不到说明 scope 用错了（私密桶没有公开 URL）。
          if (url == null || url.isEmpty) {
            throw StateError('公开桶上传没有回 publicUrl');
          }
          urls.add(url);
        } catch (e) {
          // 🔴 **一张失败不丢已成功的那几张**（code-review 2026-09-15）：
          // 整批作废等于把已经传上公开桶的对象变成孤儿，还要用户从头再选一次。
          // 记下失败、继续传剩下的，最后把成功的那些收进草稿 + 提示一次失败。
          failure = e;
        }
      }
      if (!mounted) return;
      setState(() {
        _touch(_Field.photos);
        _draft = _draft.addPhotos(urls);
      });
      if (failure != null) {
        showAppToast(
            context,
            failure is ImageProcessingException
                ? l10n.placeMarkPhotoTooLarge
                : l10n.placeMarkPhotoUploadFailed);
      }
    } on ImageProcessingException {
      // 选图阶段就压不下 10MB：告诉用户是这张图的问题，别报成「提交失败」。
      if (mounted) showAppToast(context, l10n.placeMarkPhotoTooLarge);
    } catch (_) {
      if (mounted) showAppToast(context, l10n.placeMarkPhotoUploadFailed);
    } finally {
      if (mounted) setState(() => _uploading = false);
    }
  }

  /// 提交（AC6/AC7）。
  ///
  /// 🔴 **必填未满时请求不发出**：按钮本来就是灰的，这里再挡一次是纵深防御
  /// （防某次重构把 enabled 算错）。此时把所有字段标成「碰过」让内联错误全部现形，
  /// **不叠加**通用「提交失败」横幅 —— 那会让用户以为是网络问题。
  Future<void> _onSubmit() async {
    if (!_draft.canSubmit) {
      setState(() => _touched.addAll(_Field.values));
      return;
    }
    final l10n = AppLocalizations.of(context);
    final navigator = Navigator.of(context);
    setState(() => _submitting = true);
    try {
      await ref.read(placeRepositoryProvider).createPlace(
            name: _draft.name.trim(),
            type: _draft.type!,
            tags: _draft.tags.toList(growable: false),
            latitude: _draft.latitude!,
            longitude: _draft.longitude!,
            addressText: _draft.addressText.trim(),
            photoUrls: _draft.photoUrls,
            description:
                _draft.description.trim().isEmpty ? null : _draft.description.trim(),
            idempotencyKey: _idempotencyKey,
          );
      if (!mounted) return;
      // 列表要把新场所显示出来 —— 不 invalidate 的话用户返回后看到的还是旧列表，
      // 会以为标记没成功。
      ref.invalidate(placeListProvider);
      showAppToast(context, l10n.placeMarkSuccess);
      // AC6：返回列表（详情页是 Story 1.5，那时可改成 replace 到详情）。
      navigator.pop(true);
    } on DioException catch (e) {
      if (!mounted) return;
      // 🔴 **确定性失败不能报「请重试」**（code-review 2026-09-15）：审核硬拦截与限流
      // 重试一万次结果都一样，一句「请重试」会把用户按在一个死循环里。
      final slug = ProblemDetail.fromDioException(e)?.typeSlug;
      final status = e.response?.statusCode;
      showAppToast(context, switch (slug) {
        'content-text-blocked' => l10n.placeMarkTextBlocked,
        'content-image-blocked' => l10n.placeMarkImageBlocked,
        'rate-limited' => l10n.placeMarkRateLimited,
        _ => status == 429 ? l10n.placeMarkRateLimited : l10n.placeMarkSubmitFailed,
      });
    } catch (_) {
      if (mounted) {
        showAppToast(context, l10n.placeMarkSubmitFailed);
      }
    } finally {
      if (mounted) setState(() => _submitting = false);
    }
  }

  Widget _field({
    required String label,
    required bool required,
    required String? error,
    required Widget child,
  }) {
    return Padding(
      padding: const EdgeInsets.only(bottom: AppSpacing.lg),
      child: Column(
        crossAxisAlignment: CrossAxisAlignment.start,
        children: [
          Row(
            children: [
              Text(label, style: AppTypography.body.copyWith(fontWeight: FontWeight.w600)),
              if (required)
                Text(' *', style: AppTypography.body.copyWith(color: AppColors.popRed)),
            ],
          ),
          const SizedBox(height: AppSpacing.sm),
          child,
          if (error != null) _ErrorText(error),
        ],
      ),
    );
  }
}

/// AC4 的前置告知条。**不是 Toast、不是提交时的弹窗** —— 用户开始填之前就要看到。
class _ImmutableNotice extends StatelessWidget {
  const _ImmutableNotice({required this.text});

  final String text;

  @override
  Widget build(BuildContext context) {
    return Container(
      padding: const EdgeInsets.all(AppSpacing.md),
      decoration: BoxDecoration(
        color: AppColors.goldTint,
        borderRadius: BorderRadius.circular(12),
      ),
      child: Row(
        crossAxisAlignment: CrossAxisAlignment.start,
        children: [
          const Icon(Icons.info_outline_rounded, size: 16, color: AppColors.tipsBadgeText),
          const SizedBox(width: AppSpacing.sm),
          Expanded(
            child: Text(text,
                style: AppTypography.caption.copyWith(color: AppColors.tipsBadgeText)),
          ),
        ],
      ),
    );
  }
}

class _ErrorText extends StatelessWidget {
  const _ErrorText(this.text);

  final String text;

  @override
  Widget build(BuildContext context) {
    return Padding(
      padding: const EdgeInsets.only(top: AppSpacing.xs),
      child: Text(text,
          style: AppTypography.caption.copyWith(color: AppColors.popRed)),
    );
  }
}

/// 位置行（AC3）。拿到坐标 → 打勾；没有 → 明确说需要位置 + 给开启按钮。
///
/// 🛡 **不显示坐标数值**：那是 PII，而且对用户毫无意义（他要的是「位置拿到了」这个确认）。
class _LocationRow extends StatelessWidget {
  const _LocationRow({
    required this.hasLocation,
    required this.needsPermission,
    required this.locating,
    required this.onEnable,
    required this.onRetry,
    required this.onPickOnMap,
  });

  final bool hasLocation;
  final bool needsPermission;

  /// 定位链路正在跑（转圈，不给按钮 —— 否则用户会连点好几次）。
  final bool locating;
  final VoidCallback onEnable;

  /// 权限有了但定点没拿到时的「重新定位」。
  final VoidCallback onRetry;

  /// 地图选点（Story 1.4）。
  final VoidCallback onPickOnMap;

  @override
  Widget build(BuildContext context) {
    final l10n = AppLocalizations.of(context);
    return Container(
      padding: const EdgeInsets.symmetric(
          horizontal: AppSpacing.md, vertical: AppSpacing.sm),
      decoration: BoxDecoration(
        color: hasLocation ? AppColors.mintTint : AppColors.goldTint,
        borderRadius: BorderRadius.circular(12),
      ),
      child: Column(
        crossAxisAlignment: CrossAxisAlignment.stretch,
        children: [
          Row(
            children: [
              Icon(
                  hasLocation
                      ? Icons.check_circle_outline_rounded
                      : Icons.place_outlined,
                  size: 16,
                  color: hasLocation ? AppColors.mint700 : AppColors.tipsBadgeText),
              const SizedBox(width: AppSpacing.sm),
              Expanded(
                child: Text(
                  hasLocation
                      ? l10n.placeMarkLocationReady
                      : l10n.placeMarkLocationNeeded,
                  style: AppTypography.caption.copyWith(
                      color: hasLocation ? AppColors.mint700 : AppColors.tipsBadgeText),
                ),
              ),
              // 🔴 没有坐标时**永远有一个可点的东西**：缺权限 → 「开启定位」；
              // 权限有了但没定点 → 「重新定位」。两者都没有的话用户就卡死在这一页了。
              if (!hasLocation)
                if (locating)
                  const Padding(
                    padding: EdgeInsets.symmetric(horizontal: AppSpacing.md),
                    child: SizedBox(
                        width: 16, height: 16,
                        child: CircularProgressIndicator(strokeWidth: 2)),
                  )
                else
                  TextButton(
                    key: const ValueKey('placeMarkEnableLocation'),
                    onPressed: needsPermission ? onEnable : onRetry,
                    style: TextButton.styleFrom(
                      minimumSize: const Size(44, 44),
                      foregroundColor: AppColors.tipsBadgeText,
                    ),
                    child: Text(
                        needsPermission
                            ? l10n.placeLocationEnable
                            : l10n.placeMarkLocationRetry,
                        style: AppTypography.caption.copyWith(
                            color: AppColors.tipsBadgeText,
                            fontWeight: FontWeight.w700)),
                  ),
            ],
          ),
          // Story 1.4：地图选点。**无论有没有定位权限都可点** —— 「人不在现场也能标」
          // 正是 2026-08-28 把「必须现场标记」改成地图选点的原因，
          // 把它藏在「有权限」后面等于把那条决策废掉一半。
          Align(
            alignment: Alignment.centerLeft,
            child: TextButton.icon(
              key: const ValueKey('placeMarkPickOnMap'),
              onPressed: onPickOnMap,
              style: TextButton.styleFrom(
                minimumSize: const Size(44, 44),
                foregroundColor: hasLocation ? AppColors.mint700 : AppColors.tipsBadgeText,
                padding: EdgeInsets.zero,
              ),
              icon: const Icon(Icons.map_outlined, size: 16),
              label: Text(l10n.placeMarkPickOnMap,
                  style: AppTypography.caption.copyWith(
                      color: hasLocation ? AppColors.mint700 : AppColors.tipsBadgeText,
                      fontWeight: FontWeight.w700)),
            ),
          ),
        ],
      ),
    );
  }
}

class _ChoiceChip extends StatelessWidget {
  const _ChoiceChip({required this.label, required this.selected, required this.onTap});

  final String label;
  final bool selected;
  final VoidCallback onTap;

  @override
  Widget build(BuildContext context) {
    return Material(
      color: selected ? AppColors.mint : AppColors.cream2,
      borderRadius: BorderRadius.circular(999),
      clipBehavior: Clip.antiAlias,
      child: InkWell(
        onTap: onTap,
        child: Container(
          // 竖向 12 + 文本行高 ≈ 44（UX-DR16 热区）。
          padding: const EdgeInsets.symmetric(horizontal: AppSpacing.md, vertical: 12),
          child: Text(label,
              style: AppTypography.caption.copyWith(
                color: selected ? Colors.white : AppColors.ink2,
                fontWeight: selected ? FontWeight.w700 : FontWeight.w500,
              )),
        ),
      ),
    );
  }
}

/// 照片九宫格（1–9 张）。
class _PhotoGrid extends StatelessWidget {
  const _PhotoGrid({
    required this.urls,
    required this.uploading,
    required this.onAdd,
    required this.onRemove,
  });

  final List<String> urls;
  final bool uploading;
  final VoidCallback onAdd;
  final ValueChanged<int> onRemove;

  @override
  Widget build(BuildContext context) {
    const cell = 76.0;
    return Wrap(
      spacing: AppSpacing.sm,
      runSpacing: AppSpacing.sm,
      children: [
        for (var i = 0; i < urls.length; i++)
          SizedBox(
            width: cell,
            height: cell,
            child: Stack(
              fit: StackFit.expand,
              children: [
                ClipRRect(
                  borderRadius: BorderRadius.circular(10),
                  child: AppImage.widget(urls[i], fit: BoxFit.cover),
                ),
                Positioned(
                  right: 0,
                  top: 0,
                  // 44×44 热区（UX-DR16）：删除是破坏性动作，更不能做成一个小叉。
                  child: SizedBox(
                    width: 44,
                    height: 44,
                    child: IconButton(
                      padding: EdgeInsets.zero,
                      onPressed: () => onRemove(i),
                      icon: const Icon(Icons.cancel, size: 20, color: Colors.white),
                    ),
                  ),
                ),
              ],
            ),
          ),
        if (urls.length < PlaceFormDraft.photoMaxCount)
          SizedBox(
            width: cell,
            height: cell,
            child: Material(
              color: AppColors.cream2,
              borderRadius: BorderRadius.circular(10),
              clipBehavior: Clip.antiAlias,
              child: InkWell(
                key: const ValueKey('placeMarkAddPhoto'),
                onTap: uploading ? null : onAdd,
                child: Center(
                  child: uploading
                      ? const SizedBox(
                          width: 20, height: 20,
                          child: CircularProgressIndicator(strokeWidth: 2))
                      : const Icon(Icons.add_a_photo_outlined,
                          color: AppColors.textTertiary),
                ),
              ),
            ),
          ),
      ],
    );
  }
}

class _SubmitBar extends StatelessWidget {
  const _SubmitBar({
    required this.enabled,
    required this.submitting,
    required this.label,
    required this.onSubmit,
  });

  final bool enabled;
  final bool submitting;
  final String label;
  final VoidCallback onSubmit;

  @override
  Widget build(BuildContext context) {
    return Padding(
      padding: const EdgeInsets.all(AppSpacing.lg),
      child: SizedBox(
        width: double.infinity,
        height: 48,
        child: FilledButton(
          key: const ValueKey('placeMarkSubmit'),
          // AC2：必填未满 → null（灰色禁用态）。
          onPressed: enabled ? onSubmit : null,
          child: submitting
              ? const SizedBox(
                  width: 20, height: 20,
                  child: CircularProgressIndicator(strokeWidth: 2, color: Colors.white))
              : Text(label),
        ),
      ),
    );
  }
}
