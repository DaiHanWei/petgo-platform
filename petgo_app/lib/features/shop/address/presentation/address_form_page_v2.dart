/// 新增收货地址 —— **设计稿版式**（V1.4.0 · `01_screens_browse_order.md` 屏 8）。
///
/// ⚠️ 2026-08-28：v1 版式已整体删除，本文件是该页唯一实现（`_v2` 后缀保留以免制造纯改名 diff）。
///
/// ## 🔴 三条来自设计稿的硬规则
///
/// 1. **行政区三级级联，不做自由输入** —— 服务范围判定依赖标准化行政区划。
///    让用户自己打字，服务范围就永远对不上，且这种错在下单前无从发现。
/// 2. **范围提示只告知、不阻断保存**（FR-98 / FR-99 的分界）。用户可能为将来备一个地址；
///    真正的拦截发生在**结算**。这里把保存挡掉只会让人以为「这个地址存不了」。
/// 3. **patokan（地标）与门牌同一个多行字段** —— 印尼地址实践中门牌常需地标补充。
///    拆成两栏的结果是用户只填一栏，另一栏空着。
///
/// ## 校验时机
///
/// 失焦即校验单字段（红边 + 字段下一行说明）；点保存全量校验并**滚到第一个错误**。
/// 🔴 滚动用容器偏移计算，**不用 `Scrollable.ensureVisible`** ——
/// 后者会把目标顶到视口正中，在带底部固定条的表单里经常把错误项推到条底下看不见。
library;

import 'package:flutter/material.dart';
import 'package:flutter/rendering.dart';
import 'package:flutter/services.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:go_router/go_router.dart';

import '../../../../core/theme/shop_tokens.dart';
import '../../../../l10n/app_localizations.dart';
import '../../presentation/widgets/shop_buttons.dart';
import '../../presentation/widgets/shop_controls.dart';
import '../../presentation/widgets/shop_decor.dart';
import '../../presentation/widgets/shop_surface.dart';
import '../data/address_repository.dart';
import '../domain/shipping_address.dart';

class AddressFormPageV2 extends ConsumerStatefulWidget {
  const AddressFormPageV2({super.key, this.token});

  /// 非空 = 编辑既有地址。
  final String? token;

  @override
  ConsumerState<AddressFormPageV2> createState() => _AddressFormPageV2State();
}

class _AddressFormPageV2State extends ConsumerState<AddressFormPageV2> {
  final _scroll = ScrollController();
  final _receiver = TextEditingController();
  final _phone = TextEditingController();
  final _kodePos = TextEditingController();
  final _line = TextEditingController();

  String? _provinsi;
  String? _kota;
  String? _kecamatan;
  String? _label;
  bool _makeDefault = false;
  bool _saving = false;
  String? _submitError;

  /// 字段 key → 错误文案。null / 缺失 = 该字段无错。
  final Map<String, String?> _errors = {};

  /// 各字段的 GlobalKey，用于「滚到第一个错误」时取它在滚动容器内的偏移。
  final Map<String, GlobalKey> _fieldKeys = {
    'receiver': GlobalKey(),
    'phone': GlobalKey(),
    'region': GlobalKey(),
    'kodePos': GlobalKey(),
    'line': GlobalKey(),
    'label': GlobalKey(),
  };

  /// 校验顺序 == 视觉顺序。滚到「第一个」错误依赖这个顺序，别重排。
  /// 🔴 `label` 是 2026-09-02 补进来的（D-17）：它**是必填**（服务端会拒），
  /// 但此前既不在这个序列里、界面上也没有任何必填标记 ⇒ 端上不校验、直接提交、
  /// 服务端拒绝 ⇒ 落到 `_submitError`，而那块提示画在表单**顶部**，
  /// 用户是在页面**底部**点的保存 —— 于是「页面纹丝不动」。
  /// ⚠️ 顺序即滚动定位顺序，label 排在最后（它在表单里也确实最靠下）。
  static const _fieldOrder = ['receiver', 'phone', 'region', 'kodePos', 'line', 'label'];

  @override
  void dispose() {
    _scroll.dispose();
    _receiver.dispose();
    _phone.dispose();
    _kodePos.dispose();
    _line.dispose();
    super.dispose();
  }

  @override
  Widget build(BuildContext context) {
    final l10n = AppLocalizations.of(context);
    final tree = ref.watch(regionTreeProvider);

    return Scaffold(
      backgroundColor: ShopColors.bg,
      appBar: ShopAppBar(title: l10n.addressAdd),
      body: tree.when(
        loading: () => const Center(child: CircularProgressIndicator()),
        error: (_, _) => Center(
          child: Padding(
            padding: const EdgeInsets.all(32),
            child: Text(l10n.addressLoadFailed,
                textAlign: TextAlign.center, style: ShopText.body),
          ),
        ),
        data: (t) => _form(l10n, t),
      ),
      bottomNavigationBar: ShopBottomBarActions(
        primary: ShopButton(
          key: const ValueKey('addressSaveV2'),
          label: l10n.addressSave,
          variant: _saving ? ShopButtonVariant.disabled : ShopButtonVariant.pay,
          onTap: _saving ? null : () => _save(l10n),
        ),
      ),
    );
  }

  Widget _form(AppLocalizations l10n, RegionTree tree) {
    final provinsiList = tree.provinsi;
    final kotaList =
        provinsiList.where((p) => p.name == _provinsi).expand((p) => p.kota).toList();
    final kecamatanList =
        kotaList.where((k) => k.name == _kota).expand((k) => k.kecamatan).toList();

    return ListView(
      controller: _scroll,
      padding: EdgeInsets.zero,
      children: [
        // ---- 块 1：收件人 ----
        ShopSection(
          child: Column(
            crossAxisAlignment: CrossAxisAlignment.start,
            children: [
              Text(l10n.addressSectionReceiver, style: ShopText.sectionTitle.copyWith(fontSize: 12)),
              const SizedBox(height: 10),
              _field(
                id: 'receiver',
                label: l10n.addressReceiverName,
                controller: _receiver,
                onBlur: () => _validateOne(l10n, 'receiver'),
              ),
              const SizedBox(height: 11),
              _field(
                id: 'phone',
                label: l10n.addressReceiverPhone,
                controller: _phone,
                keyboardType: TextInputType.phone,
                helper: l10n.addressPhoneHelper,
                onBlur: () => _validateOne(l10n, 'phone'),
              ),
            ],
          ),
        ),

        // ---- 块 2：地址 ----
        ShopSection(
          child: Column(
            key: _fieldKeys['region'],
            crossAxisAlignment: CrossAxisAlignment.start,
            children: [
              Text(l10n.addressSectionAddress, style: ShopText.sectionTitle.copyWith(fontSize: 12)),
              const SizedBox(height: 10),
              // 🔴 三级级联，**不做自由输入**。上级换了就清空下级 ——
              //    留着旧的下级会拼出一个不存在的行政区组合。
              _picker(
                id: 'provinsi',
                label: l10n.addressProvinsi,
                value: _provinsi,
                options: [for (final p in provinsiList) (p.name, true)],
                onChanged: (v) => setState(() {
                  _provinsi = v;
                  _kota = null;
                  _kecamatan = null;
                }),
              ),
              const SizedBox(height: 11),
              _picker(
                id: 'kota',
                label: l10n.addressKota,
                value: _kota,
                enabled: _provinsi != null,
                options: [for (final k in kotaList) (k.name, true)],
                onChanged: (v) => setState(() {
                  _kota = v;
                  _kecamatan = null;
                }),
              ),
              const SizedBox(height: 11),
              _picker(
                id: 'kecamatan',
                label: l10n.addressKecamatan,
                value: _kecamatan,
                enabled: _kota != null,
                // 不可配送的 Kecamatan **仍然可选**（规则 2：只告知不阻断），
                // 但在选项里就标出来，免得用户选完才发现。
                options: [
                  for (final k in kecamatanList)
                    (k.serviceable ? k.name : '${k.name} · ${l10n.addressNotServiceable}',
                        k.serviceable)
                ],
                onChanged: (v) => setState(() {
                  _kecamatan = v == null ? null : _stripSuffix(v, l10n);
                  _errors['region'] = null;
                }),
              ),
              if (_errors['region'] != null) _errorLine(_errors['region']!),
              const SizedBox(height: 11),
              _field(
                id: 'kodePos',
                label: l10n.addressKodePos,
                controller: _kodePos,
                keyboardType: TextInputType.number,
                inputFormatters: [
                  FilteringTextInputFormatter.digitsOnly,
                  LengthLimitingTextInputFormatter(5),
                ],
                onBlur: () => _validateOne(l10n, 'kodePos'),
              ),
              const SizedBox(height: 11),
              _field(
                id: 'line',
                label: l10n.addressLine,
                controller: _line,
                // 🔴 门牌与 patokan 同一个多行字段（见文件头规则 3）。
                hint: l10n.addressLineHint,
                minLines: 3,
                maxLines: 5,
                onBlur: () => _validateOne(l10n, 'line'),
              ),
              if (_kecamatan != null) ...[
                const SizedBox(height: 11),
                _serviceHint(l10n, kecamatanList),
              ],
            ],
          ),
        ),

        // ---- 块 3：标签与默认 ----
        ShopSection(
          child: Column(
            crossAxisAlignment: CrossAxisAlignment.start,
            children: [
              // 🔴 D-17：必填却**没有任何必填标记**，用户不知道这里非选不可。
              //    key 供校验失败时滚动定位（见 _fieldOrder 的说明）。
              Row(
                key: _fieldKeys['label'],
                children: [
                  Text(l10n.addressSectionLabel,
                      style: ShopText.sectionTitle.copyWith(fontSize: 12)),
                  Text(' *',
                      style: ShopText.sectionTitle
                          .copyWith(fontSize: 12, color: ShopColors.errorText)),
                ],
              ),
              const SizedBox(height: 10),
              Wrap(
                spacing: 6,
                runSpacing: 6,
                children: [
                  for (final tag in const ['Rumah', 'Kantor', 'Lainnya'])
                    ShopChip(
                      key: ValueKey('addressLabel_$tag'),
                      label: tag,
                      selected: _label == tag,
                      // 再点一次取消选中 —— 但取消后就是空，保存时会被拦下并标红。
                      onTap: () => setState(() => _label = _label == tag ? null : tag),
                    ),
                ],
              ),
              if (_errors['label'] != null) ...[
                const SizedBox(height: 6),
                Text(_errors['label']!,
                    key: const ValueKey('addressLabelError'),
                    style: ShopText.meta.copyWith(color: ShopColors.errorText)),
              ],
              const ShopDivider(margin: EdgeInsets.symmetric(vertical: 12)),
              _defaultSwitchRow(l10n),
            ],
          ),
        ),
        if (_submitError != null)
          ShopSection(
            child: Text(_submitError!,
                key: const ValueKey('addressSubmitError'),
                // 提交失败提示：错误态用 error 系而非强调色——换紫后若跟着变紫，
                // 「这里出错了」就读不出来了。
                style: ShopText.body.copyWith(color: ShopColors.errorText)),
          ),
        const SizedBox(height: kShopGutter),
      ],
    );
  }

  /// 默认地址开关。
  ///
  /// 🔴 **首个地址强制为默认且开关置灰**（设计稿）—— 一个「唯一的地址却不是默认地址」
  /// 的状态在结算页会表现为「没有可用地址」，用户完全无从理解。
  Widget _defaultSwitchRow(AppLocalizations l10n) {
    final isFirst = ref.watch(addressListProvider).maybeWhen(
          data: (list) => list.isEmpty,
          orElse: () => false,
        );
    return Row(
      children: [
        Expanded(
          child: Column(
            crossAxisAlignment: CrossAxisAlignment.start,
            children: [
              Text(l10n.addressMakeDefault, style: ShopText.cardTitle.copyWith(fontSize: 11.5)),
              if (isFirst)
                Text(l10n.addressFirstIsDefault, style: ShopText.meta),
            ],
          ),
        ),
        ShopSwitch(
          key: const ValueKey('addressDefaultSwitch'),
          value: _makeDefault,
          alwaysOn: isFirst,
          large: false,
          onChanged: (v) => setState(() => _makeDefault = v),
        ),
      ],
    );
  }

  /// 服务范围提示：范围内紫条、范围外橙条。**两者都不阻断保存**。
  Widget _serviceHint(AppLocalizations l10n, List<RegionKecamatan> kecamatanList) {
    final hit = kecamatanList.where((k) => k.name == _kecamatan).firstOrNull;
    if (hit == null) return const SizedBox.shrink();
    if (hit.serviceable) {
      return ShopLeftAccentBlock.pawcoin(
        key: const ValueKey('addressInRangeHint'),
        child: Row(
          children: [
            Container(
              width: 13,
              height: 13,
              decoration:
                  const BoxDecoration(color: ShopColors.purple, shape: BoxShape.circle),
            ),
            const SizedBox(width: 8),
            Expanded(
              child: Text(l10n.addressInServiceArea,
                  style: ShopText.body.copyWith(color: ShopColors.purpleText)),
            ),
          ],
        ),
      );
    }
    return ShopWarnBlock(
      key: const ValueKey('addressOutOfRangeHint'),
      title: l10n.addressNotServiceable,
      body: l10n.addressOutOfServiceArea,
    );
  }

  // ---------------------------------------------------------------- 控件

  Widget _field({
    required String id,
    required String label,
    required TextEditingController controller,
    required VoidCallback onBlur,
    String? hint,
    String? helper,
    TextInputType? keyboardType,
    List<TextInputFormatter>? inputFormatters,
    int minLines = 1,
    int maxLines = 1,
  }) {
    final error = _errors[id];
    return Column(
      key: _fieldKeys[id],
      crossAxisAlignment: CrossAxisAlignment.start,
      children: [
        Text(label, style: ShopText.meta.copyWith(fontSize: 10, fontWeight: FontWeight.w600)),
        const SizedBox(height: 4),
        Focus(
          onFocusChange: (has) {
            if (!has) onBlur();
          },
          child: TextField(
            key: ValueKey('addressField_$id'),
            controller: controller,
            keyboardType: keyboardType,
            inputFormatters: inputFormatters,
            minLines: minLines,
            maxLines: maxLines,
            style: const TextStyle(fontSize: 12, fontWeight: FontWeight.w500),
            decoration: InputDecoration(
              hintText: hint,
              hintStyle: ShopText.body.copyWith(color: ShopColors.text4),
              isDense: true,
              contentPadding: const EdgeInsets.symmetric(horizontal: 12, vertical: 11),
              // 错误只改描边色 + 下方一行说明（设计稿），不用 Material 的 errorText
              // ——那会把控件整体上移，一片红边时页面会跳动。
              border: _border(ShopColors.border),
              // 错误边框保持红（ShopColors.error）：强调色已改为品牌紫，
              // 若错误边框也用强调色，正常聚焦态与错误态会同为紫、无法区分。
              enabledBorder: _border(error == null ? ShopColors.border : ShopColors.error),
              focusedBorder: _border(error == null ? ShopColors.purple : ShopColors.error),
            ),
          ),
        ),
        if (error != null) _errorLine(error),
        if (error == null && helper != null)
          Padding(
            padding: const EdgeInsets.only(top: 3),
            child: Text(helper, style: ShopText.meta.copyWith(fontSize: 9.5)),
          ),
      ],
    );
  }

  OutlineInputBorder _border(Color c) => OutlineInputBorder(
        borderRadius: BorderRadius.circular(ShopShape.radiusField),
        borderSide: BorderSide(color: c),
      );

  Widget _errorLine(String text) => Padding(
        padding: const EdgeInsets.only(top: 3),
        child: Text(text,
            style: ShopText.meta.copyWith(fontSize: 9.5, color: ShopColors.error)),
      );

  /// 级联选择器。`(显示名, 是否在服务范围)`。
  Widget _picker({
    required String id,
    required String label,
    required String? value,
    required List<(String, bool)> options,
    required ValueChanged<String?> onChanged,
    bool enabled = true,
  }) {
    return Column(
      crossAxisAlignment: CrossAxisAlignment.start,
      children: [
        Text(label, style: ShopText.meta.copyWith(fontSize: 10, fontWeight: FontWeight.w600)),
        const SizedBox(height: 4),
        DropdownButtonFormField<String>(
          key: ValueKey('addressPicker_$id'),
          // 受控值。kecamatan 的显示名可能带「· belum dilayani」后缀，
          // 故按「去掉后缀后相等」回找显示串，而不是拿裸值直接比 —— 后者会让
          // 不可配送的项选中后显示空白（用户以为没选上，反复点）。
          initialValue: value == null
              ? null
              : options
                  .map((o) => o.$1)
                  .where((n) => n == value || n.startsWith('$value \u00B7 '))
                  .firstOrNull,
          isExpanded: true,
          style: const TextStyle(fontSize: 12, fontWeight: FontWeight.w500, color: ShopColors.text),
          decoration: InputDecoration(
            isDense: true,
            contentPadding: const EdgeInsets.symmetric(horizontal: 12, vertical: 11),
            border: _border(ShopColors.border),
            enabledBorder: _border(value == null ? ShopColors.border : ShopColors.purple),
            disabledBorder: _border(ShopColors.border2),
          ),
          hint: Text(label, style: ShopText.body.copyWith(color: ShopColors.text4)),
          items: [
            for (final (name, _) in options)
              DropdownMenuItem<String>(value: name, child: Text(name, overflow: TextOverflow.ellipsis)),
          ],
          onChanged: enabled ? onChanged : null,
        ),
      ],
    );
  }

  String _stripSuffix(String v, AppLocalizations l10n) {
    final suffix = ' · ${l10n.addressNotServiceable}';
    return v.endsWith(suffix) ? v.substring(0, v.length - suffix.length) : v;
  }

  // ---------------------------------------------------------------- 校验

  /// 单字段校验（失焦时调用）。
  String? _errorFor(AppLocalizations l10n, String id) => switch (id) {
        // 必填。⚠️ **不默认选中 Rumah**：那会替用户做一次他没做过的选择，
        //    而这个标签会显示在他日后的地址列表里。宁可要求他点一下。
        'label' => _label == null ? l10n.addressRequired : null,
        'receiver' => switch (_receiver.text.trim().length) {
            0 => l10n.addressRequired,
            < 2 || > 50 => l10n.addressNameTooShort,
            _ => null,
          },
        // 🔴 手机号用与服务端**同一套**归一化口径（`normalizeIdPhone`），
        //    前端自己写一套正则必然与后端漂移。
        'phone' => _phone.text.trim().isEmpty
            ? l10n.addressRequired
            : (normalizeIdPhone(_phone.text) == null ? l10n.addressPhoneInvalid : null),
        'region' => (_provinsi == null || _kota == null || _kecamatan == null)
            ? l10n.addressRegionRequired
            : null,
        'kodePos' => switch (_kodePos.text.trim()) {
            '' => l10n.addressRequired,
            final s when s.length != 5 => l10n.addressKodePosInvalid,
            _ => null,
          },
        'line' => switch (_line.text.trim().length) {
            0 => l10n.addressRequired,
            < 10 => l10n.addressLineTooShort,
            _ => null,
          },
        _ => null,
      };

  void _validateOne(AppLocalizations l10n, String id) {
    final e = _errorFor(l10n, id);
    if (_errors[id] == e) return;
    setState(() => _errors[id] = e);
  }

  Future<void> _save(AppLocalizations l10n) async {
    // 全量校验
    final next = <String, String?>{};
    for (final id in _fieldOrder) {
      next[id] = _errorFor(l10n, id);
    }
    setState(() {
      _errors
        ..clear()
        ..addAll(next);
      _submitError = null;
    });

    final firstBad = _fieldOrder.where((id) => next[id] != null).firstOrNull;
    if (firstBad != null) {
      _scrollToField(firstBad);
      return;
    }

    setState(() => _saving = true);
    try {
      final phone = normalizeIdPhone(_phone.text)!;
      final a = ShippingAddress(
        token: widget.token ?? '',
        receiverName: _receiver.text.trim(),
        receiverPhone: phone,
        provinsi: _provinsi!,
        kotaKabupaten: _kota!,
        kecamatan: _kecamatan!,
        addressLine: _line.text.trim(),
        kodePos: _kodePos.text.trim(),
        label: _label,
        isDefault: false,
      );
      final repo = ref.read(addressRepositoryProvider);
      final saved = widget.token == null ? await repo.create(a) : await repo.update(widget.token!, a);
      // 默认地址是独立的一次调用（后端保证「默认唯一」）。
      // 首个地址由后端强制为默认，端上不再重复设置。
      if (_makeDefault && !saved.isDefault) {
        await repo.setDefault(saved.token);
      }
      ref.invalidate(addressListProvider);
      if (mounted) context.pop();
    } catch (_) {
      // 🔒 错误提示**不回显用户输入**（含 PII）。
      setState(() => _submitError = l10n.addressSaveFailed);
      // 🔴 D-17：光设上文案不够 —— 这块提示画在表单**顶部**，而用户是在**底部**
      //    点的保存，不滚过去就是「页面纹丝不动」。本轮测试第三次撞见同一形态
      //    （D-8 上传 403 静默、D-12 退货提交无提示），是全局性的反馈缺失。
      if (mounted) _scrollToSubmitError();
    } finally {
      if (mounted) setState(() => _saving = false);
    }
  }

  /// 滚到提交失败提示（它在表单顶部）。
  void _scrollToSubmitError() {
    if (!_scroll.hasClients) return;
    _scroll.animateTo(0,
        duration: const Duration(milliseconds: 240), curve: Curves.easeOut);
  }

  /// 滚到指定字段。
  ///
  /// 🔴 用容器偏移计算，**不用 `Scrollable.ensureVisible`**：后者把目标顶到视口中部，
  /// 而本页底部有固定操作条 —— 错误项经常正好被推到条底下，用户看到「保存没反应」。
  /// 这里主动多留 24px 顶部余量，让错误行连同它上面的字段标签一起可见。
  void _scrollToField(String id) {
    final ctx = _fieldKeys[id]?.currentContext;
    if (ctx == null || !_scroll.hasClients) return;
    final box = ctx.findRenderObject();
    if (box is! RenderBox) return;
    final position = _scroll.position;
    // `getOffsetToReveal(target, 0.0)` = 把目标对齐到视口**顶边**所需的滚动量。
    // 比 localToGlobal 可靠：它自己处理 sliver 与嵌套滚动的坐标换算。
    final reveal = RenderAbstractViewport.of(box).getOffsetToReveal(box, 0).offset;
    final target = (reveal - 24).clamp(position.minScrollExtent, position.maxScrollExtent);
    _scroll.animateTo(target,
        duration: const Duration(milliseconds: 240), curve: Curves.easeOut);
  }
}
