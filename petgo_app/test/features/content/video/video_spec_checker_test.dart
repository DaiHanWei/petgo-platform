import 'dart:io';
import 'dart:typed_data';

import 'package:flutter_test/flutter_test.dart';
import 'package:tailtopia/features/content/video/video_spec.dart';
import 'package:tailtopia/features/content/video/video_spec_checker.dart';

/// L0：专题线 video Story 1.5 —— MP4 规格检查器（样片见 test/fixtures/video/gen_fixtures.sh）。
void main() {
  const dir = 'test/fixtures/video';
  const checker = VideoSpecChecker();

  Future<VideoSpecResult> check(String name, {VideoSpecChecker c = checker}) =>
      c.check('$dir/$name');

  group('合格样片', () {
    test('H.264 + AAC + faststart，竖屏 720×1280', () async {
      final r = await check('ok_avc_720x1280.mp4');
      expect(r.failures, isEmpty);
      expect(r.ok, isTrue);
      expect(r.displayWidth, 720);
      expect(r.displayHeight, 1280);
      expect(r.durationMs, inInclusiveRange(3900, 4100));
      expect(r.sizeBytes, File('$dir/ok_avc_720x1280.mp4').lengthSync());
      expect(r.videoCodec, 'avc1');
    });

    test('无音轨也合格', () async {
      expect((await check('ok_no_audio.mp4')).failures, isEmpty);
    });

    test('横屏 1280×720 合格（短边 720）', () async {
      final r = await check('ok_landscape_1280x720.mp4');
      expect(r.failures, isEmpty);
      expect(r.displayWidth, 1280);
      expect(r.displayHeight, 720);
    });

    test('编码 1280×720 + 旋转 90° → 按显示方向 720×1280', () async {
      final r = await check('ok_rotated.mp4');
      expect(r.failures, isEmpty);
      expect(r.displayWidth, 720);
      expect(r.displayHeight, 1280);
    });
  });

  group('不合格样片', () {
    final cases = <String, List<VideoSpecFailure>>{
      'hevc.mp4': [VideoSpecFailure.videoCodec],
      'hdr_pq.mp4': [VideoSpecFailure.hdr],
      'not_faststart.mp4': [VideoSpecFailure.faststart],
      'location.mp4': [VideoSpecFailure.location],
      'too_big_1080x1920.mp4': [VideoSpecFailure.resolution],
      'too_long_91s.mp4': [VideoSpecFailure.duration],
      'too_short_2s.mp4': [VideoSpecFailure.duration],
      'mp3_audio.mp4': [VideoSpecFailure.audioCodec],
      'quicktime.mov': [VideoSpecFailure.format],
      'garbage.bin': [VideoSpecFailure.unreadable],
      // 多项同时不合格要全部列出（运营一次就知道全部问题）。
      'hevc_not_faststart.mp4': [VideoSpecFailure.videoCodec, VideoSpecFailure.faststart],
    };
    cases.forEach((name, expected) {
      test(name, () async {
        expect((await check(name)).failures, expected);
      });
    });

    test('HEVC 时带出实际编码 fourcc（提示里显示）', () async {
      expect((await check('hevc.mp4')).videoCodec, 'hvc1');
    });

    test('超过体积上限 → size', () async {
      final r = await check('ok_avc_720x1280.mp4', c: const VideoSpecChecker(maxBytes: 1000));
      expect(r.failures, [VideoSpecFailure.size]);
    });

    test('文件不存在 → 只有 unreadable，不抛异常', () async {
      expect((await check('nope.mp4')).failures, [VideoSpecFailure.unreadable]);
    });

    test('文件被截断 → unreadable', () async {
      final bytes = File('$dir/ok_avc_720x1280.mp4').readAsBytesSync();
      final f = await _temp('truncated.mp4', bytes.sublist(0, 200));
      expect((await checker.check(f.path)).failures, [VideoSpecFailure.unreadable]);
    });
  });

  group('位置信息的另外两种写法（样片里没有，测试时注入 moov）', () {
    test('udta/©xyz（Android 录像）', () async {
      final xyz = _box([0xA9, 0x78, 0x79, 0x7A], Uint8List.fromList('+37.5+127.0/'.codeUnits));
      final f = await _temp('xyz.mp4', _injectIntoMoov(_box('udta'.codeUnits, xyz)));
      expect((await checker.check(f.path)).failures, [VideoSpecFailure.location]);
    });

    test('meta/keys 含 com.apple.quicktime.location.ISO6709（iPhone）', () async {
      final name = 'com.apple.quicktime.location.ISO6709'.codeUnits;
      final keyEntry = BytesBuilder()
        ..add(_u32(8 + name.length))
        ..add('mdta'.codeUnits)
        ..add(name);
      final keys = _box('keys'.codeUnits,
          Uint8List.fromList([0, 0, 0, 0, ..._u32(1), ...keyEntry.toBytes()]));
      final hdlr = _box('hdlr'.codeUnits, Uint8List.fromList([0, 0, 0, 0, 0, 0, 0, 0, ...'mdta'.codeUnits, ...List.filled(13, 0)]));
      // QuickTime 风格的 meta：不是 FullBox，紧接着就是 hdlr。
      final meta = _box('meta'.codeUnits, Uint8List.fromList([...hdlr, ...keys]));
      final f = await _temp('keys.mp4', _injectIntoMoov(meta));
      expect((await checker.check(f.path)).failures, [VideoSpecFailure.location]);
    });
  });

  test('剪映 / CapCut 实际导出的样片合格', () async {
    final r = await check('capcut_export.mp4');
    expect(r.failures, isEmpty);
  }, skip: File('$dir/capcut_export.mp4').existsSync()
      ? false
      : '需要运营按《官方视频导出规格》用剪映导出一段放到 test/fixtures/video/capcut_export.mp4');

  test('检查器源码不整读文件（一致性规则 8）', () {
    final src = Directory('lib/features/content/video')
        .listSync()
        .whereType<File>()
        .where((f) => f.path.endsWith('.dart'))
        .map((f) => f.readAsStringSync())
        .join();
    expect(src.contains('readAsBytes'), isFalse);
  });
}

Uint8List _u32(int v) => Uint8List(4)..buffer.asByteData().setUint32(0, v);

Uint8List _box(List<int> type, Uint8List body) =>
    Uint8List.fromList([..._u32(8 + body.length), ...type, ...body]);

/// 把一个 box 追加到 ok 样片的 moov 末尾并修正 moov 大小（检查器不读 stco，偏移变化不影响判定）。
Uint8List _injectIntoMoov(Uint8List extra) {
  final b = File('test/fixtures/video/ok_avc_720x1280.mp4').readAsBytesSync();
  final bd = ByteData.sublistView(b);
  var off = 0;
  while (off + 8 <= b.length) {
    final size = bd.getUint32(off);
    final type = String.fromCharCodes(b.sublist(off + 4, off + 8));
    if (type == 'moov') {
      final out = BytesBuilder()
        ..add(b.sublist(0, off))
        ..add(_u32(size + extra.length))
        ..add(b.sublist(off + 4, off + size))
        ..add(extra)
        ..add(b.sublist(off + size));
      return out.toBytes();
    }
    off += size;
  }
  throw StateError('no moov');
}

Future<File> _temp(String name, List<int> bytes) async {
  final d = await Directory.systemTemp.createTemp('video_spec_');
  return File('${d.path}/$name')..writeAsBytesSync(bytes);
}
