import 'package:flutter/widgets.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:tailtopia/shared/widgets/app_image.dart';

void main() {
  group('AppImage.ossResized — OSS 列表缩略图 URL', () {
    const ossUrl =
        'https://tailtopia.oss-ap-southeast-5.aliyuncs.com/public/1/abc.jpg';

    test('OSS 图追加 resize/format/quality 处理参数', () {
      final t = AppImage.ossResized(ossUrl, width: 400);
      expect(t, '$ossUrl?x-oss-process=image/resize,w_400/format,jpg/quality,q_80');
    });

    test('quality 可自定义', () {
      final t = AppImage.ossResized(ossUrl, width: 200, quality: 60);
      expect(t, contains('image/resize,w_200/format,jpg/quality,q_60'));
    });

    test('process 值里的 / 与 , 保持字面量（不被转义）', () {
      final t = AppImage.ossResized(ossUrl, width: 400);
      expect(t, contains('w_400/format,jpg'));
      expect(t, isNot(contains('%2F')));
      expect(t, isNot(contains('%2C')));
    });

    test('width ≤ 0 原样返回', () {
      expect(AppImage.ossResized(ossUrl, width: 0), ossUrl);
      expect(AppImage.ossResized(ossUrl, width: -1), ossUrl);
    });

    test('非 OSS 网络图（如 Google 头像）不动，避免破坏 URL', () {
      const g = 'https://lh3.googleusercontent.com/a/avatar=s96';
      expect(AppImage.ossResized(g, width: 200), g);
    });

    test('asset / file / 本地路径不动', () {
      expect(AppImage.ossResized('asset:assets/seed/pet01.jpg', width: 200),
          'asset:assets/seed/pet01.jpg');
      expect(AppImage.ossResized('file:/tmp/x.jpg', width: 200), 'file:/tmp/x.jpg');
      expect(AppImage.ossResized('/tmp/x.jpg', width: 200), '/tmp/x.jpg');
    });

    test('已带 x-oss-process 的（对外去 EXIF 图）不叠加', () {
      const exif = '$ossUrl?x-oss-process=image/format,jpg';
      expect(AppImage.ossResized(exif, width: 400), exif);
    });

    test('已有其他 query 时用 & 续接', () {
      const signed = '$ossUrl?Expires=123&Signature=abc';
      final t = AppImage.ossResized(signed, width: 400);
      expect(t, startsWith('$signed&x-oss-process=image/resize,w_400'));
    });

    test('无法解析/空串原样返回', () {
      expect(AppImage.ossResized('', width: 400), '');
    });
  });

  group('AppImage.widget — thumbWidth 仅作用于网络 OSS 图', () {
    const ossUrl =
        'https://tailtopia.oss-ap-southeast-5.aliyuncs.com/public/1/abc.jpg';

    // 直接读返回的 Image 部件的 image provider（不 pump，避免触发真实网络加载）。
    test('OSS 网络图带 thumbWidth → Image.network 用缩略图 URL', () {
      final w = AppImage.widget(ossUrl, width: 100, thumbWidth: 200) as Image;
      expect((w.image as NetworkImage).url, AppImage.ossResized(ossUrl, width: 200));
    });

    test('不传 thumbWidth → 用原图 URL', () {
      final w = AppImage.widget(ossUrl) as Image;
      expect((w.image as NetworkImage).url, ossUrl);
    });

    test('asset 图忽略 thumbWidth（不当 OSS 处理）', () {
      final w = AppImage.widget('asset:assets/seed/pet01.jpg', thumbWidth: 200) as Image;
      expect((w.image as AssetImage).assetName, 'assets/seed/pet01.jpg');
    });
  });

  group('AppImage.provider — thumbWidth', () {
    test('OSS 头像带 thumbWidth → NetworkImage 用缩略图 URL', () {
      const ossUrl =
          'https://tailtopia.oss-ap-southeast-5.aliyuncs.com/public/1/avatar.jpg';
      final p = AppImage.provider(ossUrl, thumbWidth: 96) as NetworkImage;
      expect(p.url, AppImage.ossResized(ossUrl, width: 96));
    });

    test('空/null → null', () {
      expect(AppImage.provider(null, thumbWidth: 96), isNull);
      expect(AppImage.provider('', thumbWidth: 96), isNull);
    });
  });
}
