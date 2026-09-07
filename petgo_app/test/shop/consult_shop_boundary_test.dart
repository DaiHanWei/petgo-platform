import 'dart:async';
import 'dart:io';

import 'package:flutter/gestures.dart';
import 'package:flutter/material.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:go_router/go_router.dart';
import 'package:tailtopia/core/analytics/analytics.dart';
import 'package:tailtopia/core/im/im_service.dart';
import 'package:tailtopia/features/profile/data/health_record_repository.dart';
import 'package:tailtopia/features/profile/domain/health_list_item.dart';
import 'package:tailtopia/features/profile/presentation/health_list_page.dart';
import 'package:tailtopia/features/consult/domain/consult_diagnosis.dart';
import 'package:tailtopia/features/consult/presentation/consult_diagnosis_view.dart';
import 'package:tailtopia/features/consult/presentation/im_chat_placeholder.dart';
import 'package:tailtopia/l10n/app_localizations.dart';
import 'package:tailtopia/shared/boundary/shop_link_policy.dart';
import 'package:tailtopia/shared/boundary/triage_category_jump.dart';

/// 🔴🔴 Story 9.1 · L0 CI 长期看守：问诊与商品的边界（FR-110，**安全攸关**）。
///
/// **为什么写成测试而不是写进文档**：这条约束的对手不是 bug，是**持续存在的转化压力**。
/// 上线后一定会有人提「结论页加个立即购买按钮」「兽医能推荐商品转化率会高很多」——
/// 每一条单看都合理。文档拦不住这种压力，红掉的流水线才拦得住。
///
/// decision-log N-3：《战略决策快照》「靠什么赢」第一条是 AI 问诊与本地信任；
/// **此约束优先于任何转化率优化**。问诊一旦被感知为销售前端，护城河即失效。
///
/// 🔴 验收形态必须是**能力缺席**，不是「权限拦住了」：
/// 一个不存在的 import、一个不存在的按钮，改不了也绕不过。
void main() {
  group('FR-110 ① 兽医端不具备任何商品能力（源码扫描）', () {
    // 后端侧同名守卫在 ConsultShopBoundaryTest；两端各扫各的，任一端破了都红。
    const guarded = ['lib/features/vet', 'lib/features/consult', 'lib/features/triage'];

    test('🔴🔴 vet / consult / triage 三个目录不 import features/shop', () {
      final offenders = <String>[];
      for (final dir in guarded) {
        for (final f in _dartFiles(dir)) {
          for (final line in f.readAsLinesSync()) {
            final t = line.trim();
            if (!t.startsWith('import ') && !t.startsWith('export ')) continue;
            if (t.contains('features/shop') || t.contains('/shop/')) {
              offenders.add('${f.path} → $t');
            }
          }
        }
      }
      expect(offenders, isEmpty,
          reason: '🔴 问诊侧引用了 shop —— 只要能 import，就能加一个「立即购买」，'
              '而那一天起问诊就是销售前端了');
    });

    test('🔴 兽医端不存在商品选择器 / 商品搜索 / 插入商品链接的组件', () {
      // 逐个点名可疑符号，而不是模糊匹配 'product'（会误伤 production 之类）
      const banned = [
        'ShopProduct',
        'SkuPicker',
        'ProductPicker',
        'ProductSearch',
        'insertProductLink',
        'shopProductToken',
        'addToCart',
      ];
      final offenders = <String>[];
      for (final dir in guarded) {
        for (final f in _dartFiles(dir)) {
          final code = _stripComments(f.readAsStringSync());
          for (final bad in banned) {
            if (code.contains(bad)) offenders.add('${f.path} → $bad');
          }
        }
      }
      expect(offenders, isEmpty, reason: '🔴 问诊侧出现了商品选择能力的痕迹');
    });
  });

  group('FR-110 ② 兽医手填的商品链接不渲染为可点击卡片', () {
    testWidgets('🔴 结论正文里的商品链接是纯文本 —— 不可点、不成卡片', (t) async {
      const withLink = 'Coba produk ini https://tailtopia.id/shop/products/abc123 ya';
      await t.pumpWidget(_host(const ConsultDiagnosisView(
        diagnosis: ConsultDiagnosis(
          diagnosis: 'Dermatitis ringan',
          generalAdvice: withLink,
          needsMedication: false,
          medName: '',
          medFrequency: '',
          followUp: '',
          worseningSigns: '',
          clinicWithin: '',
        ),
      )));

      // 文字照原样显示 —— 我们不删兽医写的内容，只是不让它变成入口
      expect(find.text(withLink), findsOneWidget);

      // 🔴 没有任何 TextSpan 挂了手势识别器（自动 linkify 的典型形态）
      for (final w in t.widgetList<Text>(find.byType(Text))) {
        expect(_hasTapRecognizer(w.textSpan), isFalse,
            reason: '🔴 结论正文的文本挂上了点击手势 —— 那就是一个购物入口');
      }

      // 🔴 整个结论视图里没有任何可点组件
      final view = find.byType(ConsultDiagnosisView);
      for (final type in const [InkWell, GestureDetector, TextButton, ElevatedButton]) {
        expect(
          find.descendant(of: view, matching: find.byType(type)),
          findsNothing,
          reason: '🔴 结论页出现了 $type —— 结论页不该有任何可点的商业动作',
        );
      }
    });
  });

  group('FR-110 ② 会话消息体过滤（兽医手填链接）', () {
    test('🔴 商品链接被识别并降级为纯文本 —— 不删兽医写的字', () {
      const cases = [
        'coba beli ini https://tailtopia.id/shop/products/abc123',
        'lihat /shop/products/abc123 ya',
        'tailtopia://shop/products/abc123',
        'buka https://x.test/api/v1/shop/products/abc123',
      ];
      for (final text in cases) {
        expect(ShopLinkPolicy.containsShopLink(text), isTrue, reason: '没识别出：$text');
        final out = ShopLinkPolicy.neutralize(text);
        expect(ShopLinkPolicy.containsShopLink(out), isFalse,
            reason: '降级后仍能被识别成商品链接：$out');
        expect(out, contains(kNeutralizedShopLink));
      }
    });

    test('普通文本不受影响（过度过滤会让兽医以为消息发失败了）', () {
      for (final text in [
        'Kasih obat cacing 1 tablet ya',
        'lihat https://tailtopia.id/articles/deworming',
        'hubungi klinik terdekat',
      ]) {
        expect(ShopLinkPolicy.containsShopLink(text), isFalse);
        expect(ShopLinkPolicy.neutralize(text), text);
      }
    });

    testWidgets('🔴 兽医在会话里粘的商品链接，上屏时已是纯文本且不可点', (t) async {
      final fake = _FakeImService();
      await t.pumpWidget(ProviderScope(
        overrides: [imServiceProvider.overrideWithValue(fake)],
        // ImChatPlaceholder 顶层是 Expanded —— 必须给它一个 Flex 父级（同 vet_chat_test）
        child: _host(const Column(children: [ImChatPlaceholder(peerId: 'v_1')])),
      ));
      await t.pump();   // initState 订阅 + login future
      fake.emitPeer('Coba ini https://tailtopia.id/shop/products/abc123');
      await t.pump();   // 投递 broadcast 事件 → setState 标脏
      await t.pump();   // 重建出气泡

      // 原样的链接不在屏上；占位在
      expect(find.textContaining('/shop/products/abc123'), findsNothing,
          reason: '🔴 商品链接原样上屏了 —— 下一步就是有人给它加个 onTap');
      expect(find.textContaining(kNeutralizedShopLink), findsOneWidget);
      // 兽医写的其余字还在
      expect(find.textContaining('Coba ini'), findsOneWidget);

      for (final w in t.widgetList<Text>(find.byType(Text))) {
        expect(_hasTapRecognizer(w.textSpan), isFalse);
      }
    });
  });

  group('FR-110 ③ 唯一允许的关联：记录类型 → 品类', () {
    test('🔴 只有驱虫/疫苗跳品类，其余一律不跳', () {
      expect(TriageCategoryJump.categoryFor('DEWORM'), 'OBAT_VITAMIN');
      expect(TriageCategoryJump.categoryFor('VACCINE'), 'OBAT_VITAMIN');
      // 🔴「每条结论都配一个购物入口」正是要防的形态
      for (final t in ['MENSTRUATION', 'NEUTER', 'CUSTOM', 'CONSULT', '', null]) {
        expect(TriageCategoryJump.categoryFor(t), isNull, reason: '$t 不该有跳转');
        expect(TriageCategoryJump.allowsJump(t), isFalse);
      }
    });

    test('🔴 能力缺席：桥的出参只可能是品类 code，永远拿不到 SKU', () {
      // 出参落在受控词表里 —— 这是「结构上不可能带出商品」的机器可读证明。
      const categories = {'MAKANAN', 'OBAT_VITAMIN', 'CAMILAN', 'PERAWATAN'};
      for (final t in ['DEWORM', 'VACCINE', 'MENSTRUATION', 'NEUTER', 'CUSTOM', null]) {
        final out = TriageCategoryJump.categoryFor(t);
        expect(out == null || categories.contains(out), isTrue,
            reason: '桥输出了品类词表之外的东西：$out');
      }
    });

    test('🔴 桥不 import features/shop —— 否则调用方就有了通往商品域的路', () {
      for (final f in _dartFiles('lib/shared/boundary')) {
        for (final line in f.readAsLinesSync()) {
          final t = line.trim();
          if (!t.startsWith('import ')) continue;
          expect(t.contains('features/shop'), isFalse, reason: '${f.path} → $t');
        }
      }
    });
  });

  group('FR-110 ③ 品类跳转的实际形态（唯一允许的商品关联）', () {
    final records = [
      HealthListItem(
          kind: 'RECORD', id: 1, editable: true, type: 'DEWORM',
          eventDate: DateTime(2026, 3, 1)),
      HealthListItem(
          kind: 'RECORD', id: 2, editable: true, type: 'NEUTER',
          eventDate: DateTime(2026, 3, 2)),
      HealthListItem(
          kind: 'CONSULT', id: 9, editable: false, type: 'CONSULT',
          symptomSummary: 'Muntah', eventDate: DateTime(2026, 3, 3)),
    ];

    testWidgets('🔴 只有驱虫记录挂品类入口；绝育与问诊存档都没有', (t) async {
      await _pumpHealth(t, records);
      expect(find.byKey(const ValueKey('healthCategoryJump_DEWORM')), findsOneWidget);
      expect(find.byKey(const ValueKey('healthCategoryJump_NEUTER')), findsNothing);
      // 🔴 问诊存档条目一律不挂 —— 「问诊结论旁边就是购物入口」正是要防的观感
      expect(find.byKey(const ValueKey('healthCategoryJump_CONSULT')), findsNothing);
    });

    testWidgets('🔴 点它去的是【品类】而不是某个商品，埋点只带 record_type', (t) async {
      final events = <MapEntry<String, Map<String, Object>?>>[];
      Analytics.debugCaptureSink = (e, p) => events.add(MapEntry(e, p));
      addTearDown(() => Analytics.debugCaptureSink = null);

      _lastShopUri = null;
      await _pumpHealth(t, records);
      await t.tap(find.byKey(const ValueKey('healthCategoryJump_DEWORM')));
      await t.pumpAndSettle();

      // 🔴 落地是 Toko 的品类筛选，URL 里没有、也不可能有 SKU
      // 落点 URI 由 /shop 路由的 builder 现场记下 —— 比读 routerDelegate 的内部形态稳
      expect(find.text('toko'), findsOneWidget);
      final loc = _lastShopUri.toString();
      expect(loc, '/shop?category=OBAT_VITAMIN');
      expect(loc.contains('products'), isFalse,
          reason: '🔴 跳转带上了具体商品 —— FR-110 只允许跳到品类');

      final jump = events.firstWhere((e) => e.key == 'triage_category_jump_tapped');
      // 🔒 NFR-5：只带受控词表值，无宠物信息、无健康描述、无 SKU
      expect(jump.value, {'record_type': 'DEWORM'});
    });
  });
}

/// 品类跳转实际落到的 URI（由占位路由现场记录）。
Uri? _lastShopUri;

/// 挂一个最小 GoRouter 的健康记录页（跳转落点用占位页，不拉真 Toko）。
Future<GoRouter> _pumpHealth(WidgetTester t, List<HealthListItem> items) async {
  await t.binding.setSurfaceSize(const Size(500, 2000));
  addTearDown(() => t.binding.setSurfaceSize(null));
  final router = GoRouter(routes: [
    GoRoute(path: '/', builder: (_, _) => const HealthListPage()),
    GoRoute(path: '/shop', builder: (_, s) {
      _lastShopUri = s.uri;
      return const Scaffold(body: Text('toko'));
    }),
  ]);
  addTearDown(router.dispose);
  await t.pumpWidget(ProviderScope(
    overrides: [healthRecordRepositoryProvider.overrideWithValue(_FakeHealthRepo(items))],
    child: MaterialApp.router(
      locale: const Locale('id'),
      localizationsDelegates: AppLocalizations.localizationsDelegates,
      supportedLocales: AppLocalizations.supportedLocales,
      routerConfig: router,
    ),
  ));
  await t.pumpAndSettle();
  return router;
}

class _FakeHealthRepo implements HealthRecordRepository {
  _FakeHealthRepo(this._items);
  final List<HealthListItem> _items;
  @override
  Future<List<HealthListItem>> list() async => _items;
  @override
  Future<void> create(HealthRecordDraft draft) async {}
  @override
  Future<void> update(int id, HealthRecordDraft draft) async {}
  @override
  Future<void> delete(int id) async {}
}

/// 只喂入站消息的 IM 替身（不触真实腾讯 SDK）。
class _FakeImService implements ImService {
  final _incoming = StreamController<ImMessage>.broadcast();

  @override
  Future<void> loginIfNeeded() async {}
  @override
  int invalidateCredential() => 0;
  @override
  Future<void> logout({int? ifGeneration}) async {}
  @override
  Future<void> sendText({required String peerId, required String text, ChatPushSpec? push}) async {}
  @override
  Future<void> sendImage(
      {required String peerId, required String filePath, ChatPushSpec? push}) async {}
  @override
  Stream<ImMessage> onMessages(String peerId) => _incoming.stream;
  @override
  Stream<void> get inboundSignals => _incoming.stream.map((_) {});
  @override
  Future<Map<String, ImConversationSummary>> conversationSummaries(List<String> peerIds) async =>
      const {};
  @override
  Future<void> markRead(String peerId) async {}
  @override
  Future<List<ImMessage>> loadHistory(String peerId, {int count = 20}) async => const [];

  void emitPeer(String text) => _incoming.add(ImMessage(who: 'peer', text: text));
}

// ---------- 辅助 ----------

Widget _host(Widget child) => MaterialApp(
      locale: const Locale('id'),
      localizationsDelegates: AppLocalizations.localizationsDelegates,
      supportedLocales: AppLocalizations.supportedLocales,
      home: Scaffold(body: child),
    );

/// 递归找出 [span] 里有没有挂 tap 手势 —— `RichText` 自动 linkify 的痕迹。
bool _hasTapRecognizer(InlineSpan? span) {
  if (span == null) return false;
  if (span is TextSpan) {
    if (span.recognizer is TapGestureRecognizer) return true;
    for (final child in span.children ?? const <InlineSpan>[]) {
      if (_hasTapRecognizer(child)) return true;
    }
  }
  return false;
}

Iterable<File> _dartFiles(String dir) {
  final d = Directory(dir);
  if (!d.existsSync()) return const [];
  return d
      .listSync(recursive: true)
      .whereType<File>()
      .where((f) => f.path.endsWith('.dart'));
}

/// 🔴 去注释后再断言：注释里写「不做商品选择器」不该让检查变红。
String _stripComments(String src) => src
    .replaceAll(RegExp(r'/\*.*?\*/', dotAll: true), '')
    .replaceAll(RegExp(r'//[^\n]*'), '');
