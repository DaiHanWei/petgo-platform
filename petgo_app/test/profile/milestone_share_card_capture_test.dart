import 'dart:async';
import 'dart:io';
import 'dart:typed_data';

import 'package:flutter/material.dart';
import 'package:flutter/painting.dart' as painting;
import 'package:flutter_test/flutter_test.dart';
import 'package:image/image.dart' as img;
import 'package:tailtopia/features/profile/domain/celebration_pet.dart';
import 'package:tailtopia/features/profile/domain/milestone.dart';
import 'package:tailtopia/features/profile/presentation/widgets/milestone_badge.dart';
import 'package:tailtopia/features/profile/presentation/widgets/milestone_share_card.dart';
import 'package:tailtopia/features/profile/presentation/widgets/pet_species_avatar.dart';
import 'package:tailtopia/l10n/app_localizations.dart';
import 'package:tailtopia/shared/card_render/card_frame.dart';
import 'package:tailtopia/shared/card_render/card_painted_image.dart';

/// V1.4.2 social-topic Story 3.1 · AC2（D-21）：里程碑卡屏外挂载 + 等画上屏再截，**不出白图**。
///
/// 截图走真实引擎（`toImage`），故每轮 pump 之间用 `runAsync` 让真实异步推进（参考 `ts_remote_art_painted_test.dart`）。
void main() {
  setUp(() {
    MilestoneBadgeAssets.debugOverride = const {};
    CardPaintedImage.precacheSvgForTest = (_) async {};
  });
  tearDown(() {
    MilestoneBadgeAssets.debugOverride = null;
    CardPaintedImage.precacheSvgForTest = null;
    MilestoneShareCardCapture.captureForTest = null;
    CardPaintedImage.endOfFrameForTest = null;
  });

  const item = MilestoneItem(
      code: 'C-S1', title: 'x', level: MilestoneLevel.s, trigger: MilestoneTrigger.systemAuto, completed: true);

  /// 庆祝层同款屏外挂载：`Positioned` 移出可视区（不用 Offstage / Visibility / Opacity）。
  Future<GlobalKey> mount(WidgetTester tester, List<CelebrationPet> pets) async {
    final k = GlobalKey();
    await tester.pumpWidget(MaterialApp(
      localizationsDelegates: AppLocalizations.localizationsDelegates,
      supportedLocales: AppLocalizations.supportedLocales,
      home: Stack(children: [
        Positioned(
          left: -2000,
          top: 0,
          width: 360,
          child: IgnorePointer(
            child: CardFrame(
              boundaryKey: k,
              canvas: kMilestoneShareCanvas,
              child: MilestoneShareCard(item: item, pets: pets, completedAt: DateTime(2026, 10, 28)),
            ),
          ),
        ),
      ]),
    ));
    await tester.pump();
    return k;
  }

  /// 驱动截图：pump 推帧（endOfFrame）+ runAsync 让引擎 toImage / 网络回调推进；[advance] 推假时钟（超时）。
  Future<(bool, Uint8List?)> drive(WidgetTester tester, Future<Uint8List?> f,
      {Duration advance = Duration.zero, int rounds = 30}) async {
    var done = false;
    Uint8List? result;
    f.then((v) {
      result = v;
      done = true;
    });
    for (var i = 0; i < rounds && !done; i++) {
      await tester.pump(advance);
      await tester.runAsync(() => Future<void>.delayed(const Duration(milliseconds: 30)));
    }
    return (done, result);
  }

  bool isPng(Uint8List b) => b.length > 8 && b[0] == 0x89 && b[1] == 0x50 && b[2] == 0x4E && b[3] == 0x47;

  testWidgets('全部就绪 → 非空 1080×1080 PNG，且不是白图（左上卡面是紫底）', (tester) async {
    final k = await mount(tester, const [CelebrationPet(name: 'Momo', petType: 'CAT')]);
    final (done, png) = await drive(tester, captureMilestoneShareCard(k));
    expect(done, isTrue);
    expect(png, isNotNull);
    expect(isPng(png!), isTrue);
    final decoded = img.decodePng(png)!;
    expect((decoded.width, decoded.height), (1080, 1080));
    final p = decoded.getPixel(40, 400);
    expect(p.r == 255 && p.g == 255 && p.b == 255, isFalse, reason: '卡面紫底，不得是白图');
    expect(p.b, greaterThan(p.g), reason: '紫色：蓝分量高于绿分量');
  });

  testWidgets('网络头像一直不回 → 超时返回 null（不截白图）', (tester) async {
    painting.debugNetworkImageHttpClientProvider = () => _HangingHttpClient();
    final k = await mount(tester, const [
      CelebrationPet(name: 'Momo', petType: 'CAT', avatarUrl: 'https://cdn.example.com/a.jpg'),
    ]);
    final (done, png) = await drive(tester, captureMilestoneShareCard(k, timeout: const Duration(seconds: 1)),
        advance: const Duration(milliseconds: 300));
    expect(done, isTrue);
    expect(png, isNull);
    painting.debugNetworkImageHttpClientProvider = null;
  });

  testWidgets('boundary 未挂载 → null', (tester) async {
    final k = GlobalKey();
    expect(await captureMilestoneShareCard(k), isNull);
  });

  testWidgets('头像加载失败（非 200）→ 回落物种默认图，且返回非空', (tester) async {
    // flutter_test 默认的 HttpClient 对任何请求回 400。
    final k = await mount(tester, const [
      CelebrationPet(name: 'Momo', petType: 'CAT', avatarUrl: 'https://cdn.example.com/404.jpg'),
    ]);
    final (done, png) = await drive(tester, captureMilestoneShareCard(k));
    expect(done, isTrue);
    expect(find.descendant(of: find.byKey(const ValueKey('milestoneShareAvatar-0')), matching: find.byType(PetSpeciesAvatar)),
        findsOneWidget);
    expect(png, isNotNull);
  });

  testWidgets('SVG 预加载失败 → null', (tester) async {
    CardPaintedImage.precacheSvgForTest = (_) async => throw StateError('missing');
    final k = await mount(tester, const []);
    final (done, png) = await drive(tester, captureMilestoneShareCard(k));
    expect(done, isTrue);
    expect(png, isNull);
  });

  testWidgets('素材清单未读完的徽章节点不算就绪；换成真节点后放行', (tester) async {
    final k = GlobalKey();
    final pending = ValueNotifier<bool>(true);
    await tester.pumpWidget(MaterialApp(
      home: RepaintBoundary(
        key: k,
        child: ValueListenableBuilder<bool>(
          valueListenable: pending,
          builder: (_, p, _) => CardPaintedImage(
              key: ValueKey(p), image: null, pending: p, fallback: (_) => const SizedBox(width: 10, height: 10)),
        ),
      ),
    ));
    var done = false;
    bool? ok;
    CardPaintedImage.waitPainted(k.currentContext!).then((v) {
      ok = v;
      done = true;
    });
    await tester.pump();
    expect(done, isFalse);
    pending.value = false;
    for (var i = 0; i < 5 && !done; i++) {
      await tester.pump();
    }
    expect(ok, isTrue);
  });

  // ---- 复审 3-1 PLAUSIBLE-1：App 进后台不出帧时，等帧也受超时约束，不会一直挂住 ----

  testWidgets('endOfFrameWithin：一直不出帧 → 超时返回 false；剩余时间已用尽 → 立即 false', (tester) async {
    CardPaintedImage.endOfFrameForTest = () => Completer<void>().future;
    bool? ok;
    CardPaintedImage.endOfFrameWithin(const Duration(seconds: 1)).then((v) => ok = v);
    await tester.pump(const Duration(milliseconds: 500));
    expect(ok, isNull);
    await tester.pump(const Duration(milliseconds: 600));
    expect(ok, isFalse);
    expect(await CardPaintedImage.endOfFrameWithin(Duration.zero), isFalse);
  });

  testWidgets('waitPainted：图都就绪但一直不出帧 → 超时返回 false', (tester) async {
    final k = await mount(tester, const []);
    CardPaintedImage.endOfFrameForTest = () => Completer<void>().future;
    bool? ok;
    CardPaintedImage.waitPainted(k.currentContext!, timeout: const Duration(seconds: 1)).then((v) => ok = v);
    await tester.pump(const Duration(milliseconds: 1100));
    expect(ok, isFalse);
  });

  testWidgets('captureMilestoneShareCard：一直不出帧（App 进后台）→ 超时返回 null', (tester) async {
    final k = await mount(tester, const [CelebrationPet(name: 'Momo', petType: 'CAT')]);
    CardPaintedImage.endOfFrameForTest = () => Completer<void>().future;
    final (done, png) = await drive(tester, captureMilestoneShareCard(k, timeout: const Duration(seconds: 1)),
        advance: const Duration(milliseconds: 300));
    expect(done, isTrue);
    expect(png, isNull);
  });

  testWidgets('captureForTest 测试缝生效', (tester) async {
    final bytes = Uint8List.fromList(const [1, 2, 3]);
    MilestoneShareCardCapture.captureForTest = (_) async => bytes;
    expect(await captureMilestoneShareCard(GlobalKey()), same(bytes));
  });
}

/// 永不回应的 HttpClient：`getUrl` 的 Future 永不完成（模拟网络头像一直不回）。
class _HangingHttpClient implements HttpClient {
  @override
  bool autoUncompress = true;

  @override
  Future<HttpClientRequest> getUrl(Uri url) => Completer<HttpClientRequest>().future;

  @override
  dynamic noSuchMethod(Invocation invocation) => super.noSuchMethod(invocation);
}
