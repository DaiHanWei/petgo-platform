import 'dart:io';
import 'dart:typed_data';

import 'video_spec.dart';

/// 只读的 MP4 文件头解析器（专题线 video Story 1.5 · FR-7 · addendum A）。
///
/// 纯 Dart、不引入依赖。用 [RandomAccessFile] 逐个读顶层 box 头、跳过 `mdat`，
/// 只把 `moov` 读进内存（≤ [_maxMoovBytes]）—— **不整读文件**（一致性规则 8）。
///
/// 所有失败项都列出（运营一次就知道全部问题）；结构读不懂时只报 [VideoSpecFailure.unreadable]。
/// 任何解析异常都收敛成 unreadable，不向 UI 抛。
class VideoSpecChecker {
  const VideoSpecChecker({this.maxBytes = kVideoMaxBytes});

  /// 体积上限；测试里调小来触发 [VideoSpecFailure.size]。
  final int maxBytes;

  static const int _maxMoovBytes = 16 * 1024 * 1024;

  /// 位置信息的三种写法：Android/通用 `©xyz`、3GPP `loci`、iPhone 的 meta keys。
  static const String _xyz = '©xyz';

  Future<VideoSpecResult> check(String path) async {
    RandomAccessFile? raf;
    try {
      raf = await File(path).open();
      final fileLength = await raf.length();
      return await _check(raf, fileLength);
    } catch (_) {
      return VideoSpecResult.unreadable;
    } finally {
      await raf?.close();
    }
  }

  Future<VideoSpecResult> _check(RandomAccessFile raf, int fileLength) async {
    String? majorBrand;
    int? moovOffset;
    int? moovSize;
    int? moovHeader;
    int? mdatOffset;

    var off = 0;
    while (off + 8 <= fileLength) {
      await raf.setPosition(off);
      final head = await raf.read(16);
      if (head.length < 8) break;
      final bd = ByteData.sublistView(head);
      var size = bd.getUint32(0);
      final type = String.fromCharCodes(head.sublist(4, 8));
      var headerLen = 8;
      if (size == 1) {
        if (head.length < 16) return VideoSpecResult.unreadable;
        size = bd.getUint64(8);
        headerLen = 16;
      } else if (size == 0) {
        size = fileLength - off;
      }
      if (size < headerLen || off + size > fileLength) {
        return VideoSpecResult.unreadable;
      }
      if (type == 'ftyp' && majorBrand == null && size >= headerLen + 4) {
        majorBrand = String.fromCharCodes(head.sublist(headerLen, headerLen + 4));
      } else if (type == 'moov' && moovOffset == null) {
        moovOffset = off;
        moovSize = size;
        moovHeader = headerLen;
      } else if (type == 'mdat' && mdatOffset == null) {
        mdatOffset = off;
      }
      off += size;
    }
    if (moovOffset == null || moovSize! > _maxMoovBytes) {
      return VideoSpecResult.unreadable;
    }

    await raf.setPosition(moovOffset);
    final moov = await raf.read(moovSize);
    if (moov.length != moovSize) return VideoSpecResult.unreadable;

    final info = _MoovInfo();
    _parseContainer(moov, moovHeader!, moov.length, info, const []);
    final video = info.tracks.where((t) => t.handler == 'vide').firstOrNull;
    if (info.durationMs == null || video == null || video.sampleEntry == null) {
      return VideoSpecResult.unreadable;
    }

    final failures = <VideoSpecFailure>[];
    // 没有 ftyp（老式 .mov 以 wide/mdat/moov 开头）或 QuickTime 品牌 → 不是 MP4 封装（AC4：format）。
    // 顶层 box 结构本身的合法性仍由上面的扫描把关（乱码文件照旧 unreadable）。
    if (majorBrand == null || majorBrand == 'qt  ') failures.add(VideoSpecFailure.format);
    final durationMs = info.durationMs!;
    if (durationMs < kVideoMinDurationMs || durationMs > kVideoMaxDurationMs) {
      failures.add(VideoSpecFailure.duration);
    }
    if (fileLength > maxBytes) failures.add(VideoSpecFailure.size);

    final rotated = video.rotated90;
    final w = rotated ? video.height : video.width;
    final h = rotated ? video.width : video.height;
    if (w <= 0 || h <= 0) return VideoSpecResult.unreadable;
    if ((w < h ? w : h) > kVideoMaxShortSide) {
      failures.add(VideoSpecFailure.resolution);
    }
    if (video.sampleEntry != 'avc1' && video.sampleEntry != 'avc3') {
      failures.add(VideoSpecFailure.videoCodec);
    }
    for (final a in info.tracks.where((t) => t.handler == 'soun')) {
      if (a.sampleEntry != 'mp4a' || !_isAac(a.objectTypeIndication)) {
        failures.add(VideoSpecFailure.audioCodec);
        break;
      }
    }
    if (video.transfer == 16 || video.transfer == 18) {
      failures.add(VideoSpecFailure.hdr);
    }
    if (mdatOffset != null && mdatOffset < moovOffset) {
      failures.add(VideoSpecFailure.faststart);
    }
    if (info.hasLocation) failures.add(VideoSpecFailure.location);

    return VideoSpecResult(
      failures: failures,
      durationMs: durationMs,
      displayWidth: w,
      displayHeight: h,
      sizeBytes: fileLength,
      videoCodec: video.sampleEntry,
    );
  }

  static bool _isAac(int? oti) => oti == 0x40 || oti == 0x66 || oti == 0x67 || oti == 0x68;

  static const Set<String> _containers = {
    'moov', 'trak', 'mdia', 'minf', 'stbl', 'udta', 'edts', 'dinf',
  };

  /// 递归解析 [start, end) 内的子 box。[path] 是父 box 类型链（判断 udta/meta 层级用）。
  void _parseContainer(Uint8List b, int start, int end, _MoovInfo info, List<String> path) {
    final bd = ByteData.sublistView(b);
    var off = start;
    while (off + 8 <= end) {
      var size = bd.getUint32(off);
      final type = String.fromCharCodes(b.sublist(off + 4, off + 8));
      var h = 8;
      if (size == 1) {
        if (off + 16 > end) throw const FormatException('box');
        size = bd.getUint64(off + 8);
        h = 16;
      } else if (size == 0) {
        size = end - off;
      }
      if (size < h || off + size > end) throw const FormatException('box');
      final body = off + h;
      final boxEnd = off + size;

      switch (type) {
        case 'trak':
          info.tracks.add(_Track());
          _parseContainer(b, body, boxEnd, info, [...path, type]);
        case 'mvhd':
          final v = b[body];
          if (v == 1) {
            final ts = bd.getUint32(body + 20);
            final dur = bd.getUint64(body + 24);
            info.durationMs = ts == 0 ? null : (dur * 1000 ~/ ts);
          } else {
            final ts = bd.getUint32(body + 12);
            final dur = bd.getUint32(body + 16);
            info.durationMs = ts == 0 ? null : (dur * 1000 ~/ ts);
          }
        case 'tkhd':
          final t = info.tracks.lastOrNull;
          if (t != null) {
            final v = b[body];
            final m = body + (v == 1 ? 52 : 40); // 矩阵起点
            final a = bd.getInt32(m);
            final bb = bd.getInt32(m + 4);
            final c = bd.getInt32(m + 12);
            final d = bd.getInt32(m + 16);
            // 90 / 270 度：a、d 为 0，b、c 非 0（16.16 定点）。
            t.rotated90 = a == 0 && d == 0 && bb != 0 && c != 0;
            t.width = bd.getUint32(m + 36) >> 16;
            t.height = bd.getUint32(m + 40) >> 16;
          }
        case 'hdlr':
          final t = info.tracks.lastOrNull;
          if (t != null && path.isNotEmpty && path.last == 'mdia') {
            t.handler = String.fromCharCodes(b.sublist(body + 8, body + 12));
          }
        case 'stsd':
          final t = info.tracks.lastOrNull;
          if (t != null) _parseStsd(b, bd, body, boxEnd, t);
        case 'meta':
          _parseMeta(b, bd, body, boxEnd, info);
        case 'loci':
          info.hasLocation = true;
        default:
          if (type == _xyz) {
            info.hasLocation = true;
          } else if (_containers.contains(type)) {
            _parseContainer(b, body, boxEnd, info, [...path, type]);
          }
      }
      off = boxEnd;
    }
  }

  void _parseStsd(Uint8List b, ByteData bd, int body, int end, _Track t) {
    final entryCount = bd.getUint32(body + 4);
    if (entryCount == 0) return;
    final e = body + 8;
    final eSize = bd.getUint32(e);
    final fourcc = String.fromCharCodes(b.sublist(e + 4, e + 8));
    final eEnd = e + eSize;
    if (eSize < 8 || eEnd > end) throw const FormatException('stsd');
    t.sampleEntry = fourcc;
    if (t.handler == 'vide' || const {'avc1', 'avc3', 'hvc1', 'hev1'}.contains(fourcc)) {
      // VisualSampleEntry：8 头 + 6 reserved + 2 dref + 16 + 2 宽 + 2 高 + 50 = 86 字节后是子 box。
      _forEachChild(b, bd, e + 86, eEnd, (type, cBody, cEnd) {
        if (type == 'colr' && cBody + 8 <= cEnd) {
          final colourType = String.fromCharCodes(b.sublist(cBody, cBody + 4));
          if (colourType == 'nclx') {
            t.transfer = bd.getUint16(cBody + 6);
          }
        }
      });
    } else if (fourcc == 'mp4a') {
      // AudioSampleEntry：8 头 + 6 + 2 + 8 + 2 + 2 + 4 + 4 = 36 字节后是子 box（version 0）。
      // QuickTime 的 SoundDescription 有 version 1（再 +16）/ version 2（再 +36），且 esds 常包在 `wave` 里。
      if (e + 18 > eEnd) throw const FormatException('mp4a');
      final version = bd.getUint16(e + 16);
      final childStart = e + 36 + (version == 1 ? 16 : version == 2 ? 36 : 0);
      void visit(String type, int cBody, int cEnd) {
        if (type == 'esds') {
          t.objectTypeIndication = _objectTypeOf(b, cBody + 4, cEnd);
        } else if (type == 'wave') {
          _forEachChild(b, bd, cBody, cEnd, visit);
        }
      }

      _forEachChild(b, bd, childStart, eEnd, visit);
    }
  }

  /// esds：ES_Descriptor(0x03) → DecoderConfigDescriptor(0x04) 的 objectTypeIndication。
  static int? _objectTypeOf(Uint8List b, int start, int end) {
    var p = start;
    int readLen() {
      var len = 0;
      for (var i = 0; i < 4 && p < end; i++) {
        final x = b[p++];
        len = (len << 7) | (x & 0x7f);
        if (x & 0x80 == 0) break;
      }
      return len;
    }

    if (p >= end || b[p++] != 0x03) return null;
    readLen();
    if (p + 3 > end) return null;
    p += 2; // ES_ID
    final flags = b[p++];
    if (flags & 0x80 != 0) p += 2; // dependsOn_ES_ID
    if (flags & 0x40 != 0) {
      if (p >= end) return null;
      p += 1 + b[p]; // URL
    }
    if (flags & 0x20 != 0) p += 2; // OCR_ES_Id
    if (p >= end || b[p++] != 0x04) return null;
    readLen();
    return p < end ? b[p] : null;
  }

  /// meta：ISO FullBox（版本+标志 4 字节）或 QuickTime 普通 box —— 看紧随其后的是否像 `hdlr` 头来判断。
  void _parseMeta(Uint8List b, ByteData bd, int body, int end, _MoovInfo info) {
    // QuickTime 风格：body 起就是子 box，body+4 处是 `hdlr` 的类型；ISO FullBox 则先有 4 字节版本+标志。
    var start = body;
    if (!(start + 8 <= end && String.fromCharCodes(b.sublist(start + 4, start + 8)) == 'hdlr')) {
      start += 4;
    }
    _forEachChild(b, bd, start, end, (type, cBody, cEnd) {
      if (type == 'keys') {
        // keys：FullBox 4 + entry_count 4，每项 size(4) + namespace(4) + name。
        final count = bd.getUint32(cBody + 4);
        var p = cBody + 8;
        for (var i = 0; i < count && p + 8 <= cEnd; i++) {
          final ks = bd.getUint32(p);
          if (ks < 8 || p + ks > cEnd) break;
          final name = String.fromCharCodes(b.sublist(p + 8, p + ks)).toLowerCase();
          if (name.contains('location')) info.hasLocation = true;
          p += ks;
        }
      } else if (type == _xyz || type == 'loci') {
        info.hasLocation = true;
      } else if (type == 'ilst') {
        // iTunes 风格元数据：坐标在 meta/ilst/©xyz（ExifTool 的 ItemList:GPSCoordinates）。
        _forEachChild(b, bd, cBody, cEnd, (t2, _, _) {
          if (t2 == _xyz || t2 == 'loci') info.hasLocation = true;
        });
      }
    });
  }

  static void _forEachChild(Uint8List b, ByteData bd, int start, int end,
      void Function(String type, int body, int end) visit) {
    var off = start;
    while (off + 8 <= end) {
      final size = bd.getUint32(off);
      if (size < 8 || off + size > end) return;
      visit(String.fromCharCodes(b.sublist(off + 4, off + 8)), off + 8, off + size);
      off += size;
    }
  }
}

class _MoovInfo {
  int? durationMs;
  bool hasLocation = false;
  final List<_Track> tracks = [];
}

class _Track {
  String? handler;
  String? sampleEntry;
  int width = 0;
  int height = 0;
  bool rotated90 = false;
  int? transfer;
  int? objectTypeIndication;
}
