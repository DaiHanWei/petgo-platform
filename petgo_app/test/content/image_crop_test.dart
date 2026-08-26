import 'dart:typed_data';

import 'package:flutter_test/flutter_test.dart';
import 'package:image/image.dart' as img;
import 'package:tailtopia/features/content/domain/feed_image_layout.dart';
import 'package:tailtopia/features/content/domain/image_crop.dart';

/// V1.1.6 Story 3.5：发布端的裁剪判定与几何。
///
/// <p>守两件事：**什么图才该被打断**（免裁主路径不能被误伤），以及**裁下来的那块对不对**。
/// 后者用算术断言 —— 靠肉眼看截图判断裁剪结果，错一两成都看不出来。
Uint8List _jpeg(int w, int h) =>
    Uint8List.fromList(img.encodeJpg(img.Image(width: w, height: h), quality: 80));

void main() {
  group('AC2 容差区间内免裁剪', () {
    /// 🔴 端点必须算**区间内**。3:4 竖拍恰为 0.75 且最常见 ——
    /// 判成"超出"就等于每拍一张竖照片都要被裁剪框打断一次。
    test('端点 0.75 与 1.34 都不需要裁', () {
      expect(needsCrop(1200, 1600), isFalse); // 恰 0.75
      expect(needsCrop(1340, 1000), isFalse); // 恰 1.34
    });

    test('区间内的常见比例都不需要裁', () {
      expect(needsCrop(1080, 1350), isFalse); // 4:5
      expect(needsCrop(1000, 1000), isFalse); // 1:1
      expect(needsCrop(4000, 3000), isFalse); // 手机默认 4:3 横拍 ≈1.333
    });

    test('真正的长图与超宽图才需要裁', () {
      expect(needsCrop(1080, 1920), isTrue); // 9:16 竖屏长图
      expect(needsCrop(1920, 1080), isTrue); // 16:9
      expect(needsCrop(4000, 1000), isTrue); // 全景
    });

    /// 量不出宽高时不该拿裁剪框去打扰用户。
    test('宽高非法一律不打断', () {
      expect(needsCrop(0, 100), isFalse);
      expect(needsCrop(100, 0), isFalse);
      expect(needsCrop(-1, 100), isFalse);
    });

    /// 🔴 上传端与展示端必须用**同一对**常量。
    ///
    /// 两份一旦漂移，会出现"上传判免裁、展示却裁了"这种没有任何错误信息的玄学行为。
    /// 这条把两端锁在一起：谁改了展示端的区间，这里会立刻跟着变。
    test('判定用的就是展示端那对常量', () {
      expect(needsCrop((kFeedRatioMin * 1000).round(), 1000), isFalse);
      expect(needsCrop((kFeedRatioMax * 1000).round(), 1000), isFalse);
      expect(needsCrop((kFeedRatioMin * 1000).round() - 5, 1000), isTrue);
      expect(needsCrop((kFeedRatioMax * 1000).round() + 5, 1000), isTrue);
    });
  });

  group('AC2 裁剪矩形', () {
    test('目标更宽 → 宽度铺满、上下裁', () {
      // 1000×2000（0.5）裁成 1:1
      final r = computeCropRect(width: 1000, height: 2000, targetAspect: 1.0);
      expect(r.width, 1000);
      expect(r.height, 1000);
      expect(r.x, 0);
      expect(r.y, 500, reason: '居中 → 上下各去掉 500');
    });

    test('目标更窄 → 高度铺满、左右裁', () {
      // 2000×1000（2.0）裁成 4:5（0.8）
      final r = computeCropRect(width: 2000, height: 1000, targetAspect: 4 / 5);
      expect(r.height, 1000);
      expect(r.width, 800);
      expect(r.y, 0);
      expect(r.x, 600, reason: '居中 → 左右各去掉 600');
    });

    test('比例正好相等 → 一个像素都不裁', () {
      final r = computeCropRect(width: 1000, height: 1000, targetAspect: 1.0);
      expect((r.x, r.y, r.width, r.height), (0, 0, 1000, 1000));
    });

    test('对齐值决定保留哪一块', () {
      final top = computeCropRect(width: 1000, height: 2000, targetAspect: 1.0, alignY: 0);
      final bottom = computeCropRect(width: 1000, height: 2000, targetAspect: 1.0, alignY: 1);
      expect(top.y, 0);
      expect(bottom.y, 1000);
    });

    test('对齐值越界被夹住，不会算出画面外的矩形', () {
      final r = computeCropRect(width: 1000, height: 2000, targetAspect: 1.0, alignY: 9);
      expect(r.y, 1000);
      expect(r.y + r.height, lessThanOrEqualTo(2000));
    });
  });

  group('AC2 真实字节裁剪', () {
    test('裁完的图确实变成了目标比例', () {
      final out = cropToAspect(_jpeg(1000, 2000), targetAspect: 1.0);
      final size = imageSizeOf(out)!;
      expect(size.w, size.h);
    });

    test('4:5 档裁出来就是 4:5', () {
      final out = cropToAspect(_jpeg(2000, 1000), targetAspect: 4 / 5);
      final size = imageSizeOf(out)!;
      expect(size.w / size.h, closeTo(0.8, 0.01));
    });

    /// 解不开的字节不该把整个发布流程卡死。
    test('解不开的字节原样放行', () {
      final junk = Uint8List.fromList([1, 2, 3, 4]);
      expect(cropToAspect(junk, targetAspect: 1.0), junk);
    });
  });

  group('只读文件头拿宽高', () {
    /// ⚠️ 判定要对每张选中的图各做一次；整张解码 9 遍会让选完图之后卡住好几秒。
    test('读得出宽高', () {
      final size = imageSizeOf(_jpeg(640, 480))!;
      expect((size.w, size.h), (640, 480));
    });

    test('非图片字节返回 null，不抛', () {
      expect(imageSizeOf(Uint8List.fromList([0, 1, 2])), isNull);
      expect(imageSizeOf(Uint8List(0)), isNull);
    });
  });
}
