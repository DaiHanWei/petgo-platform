import 'dart:typed_data';

import 'package:flutter_test/flutter_test.dart';
import 'package:image/image.dart' as img;
import 'package:tailtopia/core/analytics/analytics.dart';
import 'package:tailtopia/features/content/domain/image_exposure.dart';

/// V1.3.4 Story 2.4：发布链路的露出区域编排 `prepareBatchExposure`。
///
/// 钉住：① 区间内的图完全不打扰（不进页、零埋点）；② 整批超界图一次进页、顺序 = 选图顺序；
/// ③ ✕ / 返回整批取消；④ 字节原样放行（`identical`，代理 PRD AC1 哈希一致）；
/// ⑤ E-8 / E-9 的 V1.3.4 口径（`target_ratio` 0x75 / 1x78 + `adjusted`，旧批次锁定源属性已删）。
Uint8List _jpeg(int w, int h) =>
    Uint8List.fromList(img.encodeJpg(img.Image(width: w, height: h), quality: 80));

/// 落在容差区间内（3:4，恰为下界 0.75）。
final Uint8List _inRange = _jpeg(1200, 1600);

/// 恰为上界 1.78。
final Uint8List _upperEdge = _jpeg(1780, 1000);

/// 超宽 3:1 全景。
final Uint8List _wide = _jpeg(3000, 1000);

/// 超竖 9:16。
final Uint8List _tall = _jpeg(1080, 1920);

void main() {
  TestWidgetsFlutterBinding.ensureInitialized();
  late List<(String, Map<String, Object>?)> seen;

  setUp(() {
    seen = <(String, Map<String, Object>?)>[];
    Analytics.debugCaptureSink = (e, p) => seen.add((e, p));
  });
  tearDown(() => Analytics.debugCaptureSink = null);

  List<Map<String, Object>?> events(String name) =>
      [for (final e in seen) if (e.$1 == name) e.$2];

  test('全部在区间内（含两个端点）→ 不调 ask、零事件、offset 全 null、字节原对象', () async {
    var asked = 0;
    final input = [_inRange, _upperEdge];
    final out = await prepareBatchExposure(input, ask: (_) async {
      asked++;
      return null;
    });
    expect(asked, 0);
    expect(out, isNotNull);
    expect(out!.map((p) => p.cropOffset), [null, null]);
    for (var i = 0; i < input.length; i++) {
      expect(identical(out[i].bytes, input[i]), isTrue);
    }
    expect(seen.where((e) => e.$1.startsWith('publish_image_crop')), isEmpty);
  });

  test('量不出尺寸的字节 → 不进页、offset null、原样放行', () async {
    final junk = Uint8List.fromList([1, 2, 3, 4]);
    var asked = 0;
    final out = await prepareBatchExposure([junk], ask: (_) async {
      asked++;
      return null;
    });
    expect(asked, 0);
    expect(identical(out!.single.bytes, junk), isTrue);
    expect(out.single.cropOffset, isNull);
    expect(seen, isEmpty);
  });

  test('混合 [区间内, 3:1, 9:16] → ask 一次收到 2 张且按选图顺序；offset 按原位回填', () async {
    final input = [_inRange, _wide, _tall];
    List<ExposureImage>? got;
    var asked = 0;
    final out = await prepareBatchExposure(input, ask: (overflow) async {
      asked++;
      got = overflow;
      return const ExposureResult(offsets: [0.2, 0.5], adjusted: [true, false]);
    });
    expect(asked, 1, reason: '整批超界图同一页，只 push 一次');
    expect(got, hasLength(2));
    expect(identical(got![0].bytes, _wide), isTrue);
    expect(identical(got![1].bytes, _tall), isTrue);
    expect((got![0].size.w, got![0].size.h), (3000, 1000));
    expect((got![1].size.w, got![1].size.h), (1080, 1920));
    expect(out!.map((p) => p.cropOffset), [null, 0.2, 0.5]);
    for (var i = 0; i < input.length; i++) {
      expect(identical(out[i].bytes, input[i]), isTrue, reason: '零裁切、零重编码');
    }
  });

  test('ask 回 null（✕ / 返回）→ 返回 null，且不报 completed', () async {
    final out = await prepareBatchExposure([_inRange, _wide], ask: (_) async => null);
    expect(out, isNull);
    expect(events('publish_image_crop_completed'), isEmpty);
    expect(events('publish_image_crop_shown'), hasLength(1), reason: 'shown 在打开页面之前就报了');
  });

  test('E-8：每张超界图一条 shown，带原始比例与本次选图总张数', () async {
    var shownBeforeAsk = -1;
    await prepareBatchExposure([_inRange, _wide, _tall], ask: (_) async {
      shownBeforeAsk = events('publish_image_crop_shown').length;
      return const ExposureResult(offsets: [0.5, 0.5], adjusted: [false, false]);
    });
    final shown = events('publish_image_crop_shown');
    expect(shownBeforeAsk, 2, reason: '在打开露出区域页之前上报');
    expect(shown, hasLength(2));
    expect(shown[0]!['original_ratio'], 3.0);
    expect(shown[1]!['original_ratio'], closeTo(0.563, 0.001));
    expect(shown.every((p) => p!['batch_size'] == 3), isTrue);
  });

  test('E-9：Lanjut 后每张超界图一条 completed，target_ratio 由越界方向决定 + adjusted', () async {
    await prepareBatchExposure([_wide, _inRange, _tall], ask: (_) async =>
        const ExposureResult(offsets: [0.1, 0.5], adjusted: [true, false]));
    final done = events('publish_image_crop_completed');
    expect(done, hasLength(2));
    expect(done[0], {'target_ratio': '1x78', 'adjusted': true});
    expect(done[1], {'target_ratio': '0x75', 'adjusted': false});
    // 属性键恰为这两个 —— 旧的批次锁定源属性不得残留。
    expect(done.every((p) => p!.keys.toSet().containsAll({'target_ratio', 'adjusted'}) && p.length == 2),
        isTrue);
  });

  test('ask 回的 offsets 少一项 → 缺的那张取默认 0.5；adjusted 缺项按 false', () async {
    final out = await prepareBatchExposure([_wide, _tall], ask: (_) async =>
        const ExposureResult(offsets: [0.0], adjusted: [true]));
    expect(out!.map((p) => p.cropOffset), [0.0, kExposureDefaultOffset]);
    expect(events('publish_image_crop_completed')[1]!['adjusted'], isFalse);
  });

  test('越界 offset 被夹到 [0,1]', () async {
    final out = await prepareBatchExposure([_wide], ask: (_) async =>
        const ExposureResult(offsets: [1.7], adjusted: [true]));
    expect(out!.single.cropOffset, 1.0);
  });
}
