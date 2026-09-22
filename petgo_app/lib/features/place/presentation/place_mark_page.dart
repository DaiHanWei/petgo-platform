import 'package:dio/dio.dart';
import 'package:flutter/material.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:go_router/go_router.dart';

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
import 'place_detail_page.dart';
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

    // AC2：必填未满 / 提交中 / 照片上传中 → 顶栏「Simpan」灰色禁用。
    final canSubmit = _draft.canSubmit && !_submitting && !_uploading;
    // 输入框的错误文字外置到 [_WithError]（与 chip 组同一套，左边缘对齐 —— UI 稿 A8），
    // 输入框自身只保留红框。
    final nameError =
        _errorFor(_Field.name, _draft.nameOk) ? l10n.placeMarkNameError : null;
    final addressError =
        _errorFor(_Field.address, _draft.addressOk) ? l10n.placeMarkAddressError : null;

    return Scaffold(
      backgroundColor: AppColors.cream,
      // UI 稿 A5：iOS 表单式顶栏 ——「Batal」在左、标题居中、「Simpan」在右。
      // 原先的吸底大按钮已删：保存动作挪到顶栏，判定 / 防重复 / 上传中禁用口径不变。
      appBar: AppBar(
        backgroundColor: AppColors.cream,
        scrolledUnderElevation: 0,
        automaticallyImplyLeading: false,
        centerTitle: true,
        leadingWidth: 88,
        leading: TextButton(
          key: const ValueKey('placeMarkCancel'),
          onPressed: () => Navigator.of(context).maybePop(),
          style: TextButton.styleFrom(
            minimumSize: const Size(44, 44),
            foregroundColor: AppColors.textSecondary,
          ),
          child: Text(l10n.commonCancel,
              style: AppTypography.body.copyWith(color: AppColors.textSecondary)),
        ),
        title: Text(l10n.placeMarkTitle),
        actions: [
          Padding(
            padding: const EdgeInsets.only(right: AppSpacing.sm),
            child: TextButton(
              key: const ValueKey('placeMarkSubmit'),
              // AC2：必填未满 → null（灰色禁用态）。
              onPressed: canSubmit ? _onSubmit : null,
              style: TextButton.styleFrom(
                minimumSize: const Size(44, 44),
                foregroundColor: AppColors.mint,
                disabledForegroundColor: AppColors.textTertiary,
              ),
              child: _submitting
                  ? const SizedBox(
                      width: 18, height: 18,
                      child: CircularProgressIndicator(strokeWidth: 2))
                  : Text(l10n.placeMarkSubmit,
                      style: AppTypography.body.copyWith(
                          fontWeight: FontWeight.w700,
                          color: canSubmit ? AppColors.mint : AppColors.textTertiary)),
            ),
          ),
        ],
      ),
      body: SafeArea(
        child: ListView(
          padding: const EdgeInsets.all(AppSpacing.lg),
          children: [
            // AC4：前置告知，放在最上面、在所有输入之前。
            _ImmutableNotice(text: l10n.placeMarkImmutableNotice),
            const SizedBox(height: AppSpacing.lg),
            _field(
              label: l10n.placeMarkNameLabel,
              required: true,
              child: _WithError(
                error: nameError,
                child: TextField(
                  controller: _nameController,
                  maxLength: PlaceFormDraft.nameMaxLength,
                  buildCounter: _noCounter,
                  decoration: _inputDecoration(
                    hint: l10n.placeMarkNameHint,
                    hasError: nameError != null,
                    length: _nameController.text.length,
                    maxLength: PlaceFormDraft.nameMaxLength,
                  ),
                  onChanged: (v) => setState(() {
                    _touch(_Field.name);
                    _draft = _draft.copyWith(name: v);
                  }),
                ),
              ),
            ),
            _field(
              label: l10n.placeMarkTypeLabel,
              required: true,
              child: _WithError(
                error: _errorFor(_Field.type, _draft.typeOk) ? l10n.placeMarkTypeError : null,
                child: Wrap(
                  spacing: AppSpacing.sm,
                  // 行距由 chip 外层 6+6 的透明热区提供（视觉行距 12），不再叠加。
                  runSpacing: 0,
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
            ),
            _field(
              label: l10n.placeMarkTagsLabel,
              required: true,
              child: _WithError(
                error: _errorFor(_Field.tags, _draft.tagsOk) ? l10n.placeMarkTagsError : null,
                child: Wrap(
                  spacing: AppSpacing.sm,
                  // 行距由 chip 外层 6+6 的透明热区提供（视觉行距 12），不再叠加。
                  runSpacing: 0,
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
            ),
            // UI 稿 A5 字段顺序：位置在前、文字地址在后（先在地图上点出来，再补一句人话地址）。
            _field(
              label: l10n.placeMarkLocationLabel,
              required: true,
              child: _WithError(
                error: _errorFor(_Field.location, _draft.hasLocation)
                    ? l10n.placeMarkLocationError
                    : null,
                child: _LocationRow(
                  hasLocation: _draft.hasLocation,
                  hasError: _errorFor(_Field.location, _draft.hasLocation),
                  // AC3：未授权定位时**明确提示需要位置**，不静默失败。
                  needsPermission: locationAsync.value?.needsPermissionBanner ?? true,
                  locating: locationAsync.isLoading,
                  onEnable: _onEnableLocation,
                  onRetry: _onRetryLocate,
                  // Story 1.4：地图选点。**替换**「只能取当前定位」那条路径，
                  // 但默认落点仍是当前位置 —— 不动针直接确认 = 与 1.3 等价。
                  onPickOnMap: _onPickOnMap,
                ),
              ),
            ),
            _field(
              label: l10n.placeMarkAddressLabel,
              required: true,
              child: _WithError(
                error: addressError,
                child: TextField(
                  controller: _addressController,
                  maxLength: PlaceFormDraft.addressMaxLength,
                  buildCounter: _noCounter,
                  maxLines: 2,
                  minLines: 1,
                  decoration: _inputDecoration(
                    hint: l10n.placeMarkAddressHint,
                    hasError: addressError != null,
                    length: _addressController.text.length,
                    maxLength: PlaceFormDraft.addressMaxLength,
                  ),
                  onChanged: (v) => setState(() {
                    _touch(_Field.address);
                    _draft = _draft.copyWith(addressText: v);
                  }),
                ),
              ),
            ),
            _field(
              label: l10n.placeMarkPhotosLabel,
              required: true,
              child: _WithError(
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
            ),
            _field(
              label: l10n.placeMarkDescriptionLabel,
              required: false,
              child: TextField(
                controller: _descriptionController,
                maxLength: PlaceFormDraft.descriptionMaxLength,
                buildCounter: _noCounter,
                maxLines: 4,
                minLines: 2,
                decoration: _inputDecoration(
                  hint: l10n.placeMarkDescriptionHint,
                  hasError: false,
                  length: _descriptionController.text.length,
                  maxLength: PlaceFormDraft.descriptionMaxLength,
                ),
                onChanged: (v) =>
                    setState(() => _draft = _draft.copyWith(description: v)),
              ),
            ),
          ],
        ),
      ),
    );
  }

  /// 去掉 Material 默认的「0/80」计数行（它在输入框下面单独占一整行空白）。
  /// 计数挪进框内右侧，见 [_inputDecoration] 的 `suffixText`。
  static Widget? _noCounter(BuildContext context,
          {required int currentLength, required bool isFocused, required int? maxLength}) =>
      null;

  /// UI 稿 A5 的圆角描边输入框：常态灰边、聚焦品牌色边、错误红边（A8）。
  ///
  /// 🔴 错误 = 红边（本 decoration）+ 框下一行红字（外置的 [_WithError]）。
  /// 红字不走本 decoration 的 `errorText` —— 那会被 contentPadding 缩进 12，
  /// 与 chip 组 / 位置行 / 照片格的错误字左边缘对不齐（UI 稿 A8）。
  /// `error: SizedBox.shrink()` 只为触发 errorBorder，不占文字行。
  InputDecoration _inputDecoration({
    required String hint,
    required bool hasError,
    required int length,
    required int maxLength,
  }) {
    OutlineInputBorder border(Color c, [double w = 1]) => OutlineInputBorder(
          borderRadius: BorderRadius.circular(12),
          borderSide: BorderSide(color: c, width: w),
        );
    return InputDecoration(
      hintText: hint,
      hintStyle: AppTypography.body.copyWith(color: AppColors.textTertiary),
      error: hasError ? const SizedBox.shrink() : null,
      suffixText: '$length/$maxLength',
      suffixStyle: AppTypography.micro,
      filled: true,
      fillColor: AppColors.card,
      isDense: true,
      contentPadding:
          const EdgeInsets.symmetric(horizontal: AppSpacing.md, vertical: 14),
      border: border(AppColors.line),
      enabledBorder: border(AppColors.line),
      focusedBorder: border(AppColors.mint, 1.5),
      errorBorder: border(AppColors.popRed),
      focusedErrorBorder: border(AppColors.popRed, 1.5),
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
    // 在 await 之前取好 router（提交可能要几秒，期间 context 可能已失效）。
    final router = GoRouter.of(context);
    setState(() => _submitting = true);
    try {
      final token = await ref.read(placeRepositoryProvider).createPlace(
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
      // 🔴 **列表的刷新由这里负责，不靠返回值**（bug 514）：成功后本页被详情页替换，
      // 列表页 `context.push` 的那个 future 永远不会完成（go_router 的 pushReplacement
      // 不完成被替换页的 completer），所以列表页不能再等 `pop(true)` 才刷新。
      // 列表页此刻仍挂在栈底，invalidate 会立即重拉 —— 用户从详情返回时看到的就是最新的。
      // 定位一并重读：用户可能在表单页停留期间移动过，回列表时族键应随之更新。
      ref.invalidate(placeListProvider);
      ref.invalidate(placeLocationProvider);
      showAppToast(context, l10n.placeMarkSuccess);
      // AC6 · UI 稿（bug 514）：提交成功后**进入新场所详情页**，底部 toast「Tempat berhasil ditandai」。
      // 🔴 用 pushReplacement 而不是 push：表单页从栈里拿掉，详情页返回即回列表 ——
      // 否则返回会落回一张已经提交过的表单，再点一次「保存」就是重复标记（幂等键兜得住，但 UX 是错的）。
      router.pushReplacement(PlaceDetailPage.routeFor(token));
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

  /// 字段外框：全大写 overline 标签（UI 稿 A5：小号、加粗、字距、次级色）+ 必填星号 + 控件。
  ///
  /// 错误不在这里画 —— 所有字段（输入框也一样）的错误字都走 [_WithError]，
  /// 左边缘一致；输入框额外由自身 decoration 画红框。
  Widget _field({
    required String label,
    required bool required,
    required Widget child,
  }) {
    return Padding(
      padding: const EdgeInsets.only(bottom: AppSpacing.lg),
      child: Column(
        crossAxisAlignment: CrossAxisAlignment.start,
        children: [
          Row(
            children: [
              Text(label.toUpperCase(),
                  style: AppTypography.micro.copyWith(
                      fontWeight: FontWeight.w700,
                      letterSpacing: 1.2,
                      color: AppColors.textSecondary)),
              if (required)
                Text(' *',
                    style: AppTypography.micro.copyWith(
                        fontWeight: FontWeight.w700, color: AppColors.popRed)),
            ],
          ),
          const SizedBox(height: AppSpacing.sm),
          child,
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

/// 全部字段（输入框 / chips / 位置行 / 照片格）的内联错误（AC7 · UI 稿 A8）。
///
/// 用无边框、零 contentPadding 的 [InputDecorator] 承载 `errorText`：所有字段的错误字
/// 同一字号颜色、同一左边缘（输入框自身只画红框，见 `_inputDecoration`）。
class _WithError extends StatelessWidget {
  const _WithError({required this.error, required this.child});

  final String? error;
  final Widget child;

  @override
  Widget build(BuildContext context) {
    return InputDecorator(
      decoration: InputDecoration(
        border: InputBorder.none,
        isDense: true,
        contentPadding: EdgeInsets.zero,
        errorText: error,
        errorStyle: AppTypography.caption.copyWith(color: AppColors.popRed),
      ),
      child: child,
    );
  }
}

/// 位置行（AC3）。拿到坐标 → 打勾；没有 → 明确说需要位置 + 给开启按钮。
///
/// 🛡 **不显示坐标数值**：那是 PII，而且对用户毫无意义（他要的是「位置拿到了」这个确认）。
class _LocationRow extends StatelessWidget {
  const _LocationRow({
    required this.hasLocation,
    required this.hasError,
    required this.needsPermission,
    required this.locating,
    required this.onEnable,
    required this.onRetry,
    required this.onPickOnMap,
  });

  final bool hasLocation;

  /// 碰过且仍没有位置（A8）→ 整行红色描边，与输入框的错误态一致。
  final bool hasError;
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
    return Column(
      crossAxisAlignment: CrossAxisAlignment.stretch,
      children: [
        // UI 稿 A5：单行可点「📍 Pilih di peta ›」。整行就是地图选点入口 ——
        // Story 1.4：**无论有没有定位权限都可点**，「人不在现场也能标」正是 2026-08-28
        // 把「必须现场标记」改成地图选点的原因，把它藏在「有权限」后面等于把那条决策废掉一半。
        Material(
          color: AppColors.mintTint,
          shape: RoundedRectangleBorder(
            borderRadius: BorderRadius.circular(12),
            side: BorderSide(color: hasError ? AppColors.popRed : AppColors.lineViolet),
          ),
          clipBehavior: Clip.antiAlias,
          child: InkWell(
            key: const ValueKey('placeMarkPickOnMap'),
            onTap: onPickOnMap,
            child: ConstrainedBox(
              // 44 热区（UX-DR16）。
              constraints: const BoxConstraints(minHeight: 52),
              child: Padding(
                padding: const EdgeInsets.symmetric(horizontal: AppSpacing.md),
                child: Row(
                  children: [
                    Icon(
                        hasLocation
                            ? Icons.check_circle_rounded
                            : Icons.location_on_rounded,
                        size: 18,
                        color: hasLocation ? AppColors.mint : AppColors.popRed),
                    const SizedBox(width: AppSpacing.sm),
                    Expanded(
                      // 已取点 →「已取点」态文案；再点一次可以在地图上改。
                      // 🛡 **不显示坐标数值**：那是 PII，而且对用户毫无意义。
                      child: Text(
                        hasLocation ? l10n.placeMarkLocationReady : l10n.placeMarkPickOnMap,
                        style: AppTypography.body.copyWith(
                            color: hasLocation ? AppColors.mint700 : AppColors.textPrimary),
                      ),
                    ),
                    // 🔴 没有坐标时**永远有一个可点的东西**：整行本身就能选点；另外
                    // 缺权限 → 「开启定位」；权限有了但没定点 → 「重新定位」。
                    if (!hasLocation)
                      if (locating)
                        const Padding(
                          padding: EdgeInsets.symmetric(horizontal: AppSpacing.sm),
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
                            foregroundColor: AppColors.mint,
                            padding: const EdgeInsets.symmetric(horizontal: AppSpacing.sm),
                          ),
                          child: Text(
                              needsPermission
                                  ? l10n.placeLocationEnable
                                  : l10n.placeMarkLocationRetry,
                              style: AppTypography.caption.copyWith(
                                  color: AppColors.mint, fontWeight: FontWeight.w700)),
                        ),
                    const Icon(Icons.chevron_right_rounded,
                        size: 20, color: AppColors.textTertiary),
                  ],
                ),
              ),
            ),
          ),
        ),
        // AC3：未授权定位时**明确提示需要位置**，不静默失败。
        if (!hasLocation && needsPermission && !hasError)
          Padding(
            padding: const EdgeInsets.only(top: AppSpacing.xs),
            child: Text(l10n.placeMarkLocationNeeded,
                style: AppTypography.caption.copyWith(color: AppColors.tipsBadgeText)),
          ),
      ],
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
    // UI 稿 A5：未选 = 白底 + 灰描边，已选 = 品牌紫实底。
    // 视觉高度 ≈ 32（竖向 6 + caption 行高 ≈ 18 + 描边）；44 热区（UX-DR16）靠外层
    // 透明的 6px 上下留白 + 最小高度 44 保证，不靠 chip 本体撑高。
    return GestureDetector(
      behavior: HitTestBehavior.opaque,
      onTap: onTap,
      child: ConstrainedBox(
        constraints: const BoxConstraints(minHeight: 44),
        child: Padding(
          padding: const EdgeInsets.symmetric(vertical: 6),
          child: Center(
            widthFactor: 1,
            child: Material(
              color: selected ? AppColors.mint : AppColors.card,
              shape: StadiumBorder(
                  side: BorderSide(color: selected ? AppColors.mint : AppColors.line)),
              clipBehavior: Clip.antiAlias,
              child: InkWell(
                onTap: onTap,
                child: Padding(
                  padding: const EdgeInsets.symmetric(
                      horizontal: AppSpacing.md, vertical: 6),
                  child: Text(label,
                      style: AppTypography.caption.copyWith(
                        color: selected ? Colors.white : AppColors.ink2,
                        fontWeight: selected ? FontWeight.w700 : FontWeight.w500,
                      )),
                ),
              ),
            ),
          ),
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
    // UI 稿 A5：56 见方 + 1px 浅紫描边（照片格与「+」格同一套边）。
    const cell = 56.0;
    final cellBorder = BoxDecoration(
      border: Border.all(color: AppColors.lineViolet),
      borderRadius: BorderRadius.circular(10),
    );
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
                Container(
                  foregroundDecoration: cellBorder,
                  child: ClipRRect(
                    borderRadius: BorderRadius.circular(10),
                    child: AppImage.widget(urls[i], fit: BoxFit.cover),
                  ),
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
              shape: RoundedRectangleBorder(
                borderRadius: BorderRadius.circular(10),
                side: const BorderSide(color: AppColors.lineViolet),
              ),
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
