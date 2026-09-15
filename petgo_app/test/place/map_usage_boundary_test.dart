import 'dart:io';

import 'package:flutter_test/flutter_test.dart';

/// V1.3.0 batch-b1 Story 1.4 · L0：**地图 SDK 的使用边界**（AC1 / AC5 · AD-3）。
///
/// <h2>为什么这条要用扫源码的方式守</h2>
/// AD-3 的边界是**费用边界**：地图「显示」在移动端免费且不限量，而
/// **地点搜索 / 自动补全（Places）、地址与坐标互转（Geocoding）、路线（Directions）
/// 才是按次收费的大头**。加一行 `geocoding` 调用不会让任何测试变红、不会让界面出错，
/// 只会在下个月的账单上出现 —— 这正是需要一条机械检查的那种约束。
///
/// 同理 AC5：地图只许出现在**选点弹层**与**详情页小地图**两处。
/// 列表页嵌地图是 PRD ⑥ 明确排除的「地图浏览视图」，而它同样不会让任何别的测试变红。
void main() {
  final libDir = Directory('lib');

  List<File> dartFiles() => libDir
      .listSync(recursive: true)
      .whereType<File>()
      .where((f) => f.path.endsWith('.dart'))
      .toList(growable: false);

  group('🔴 AC1：只用「显示地图 + 打我们自己的点」', () {
    /// 按次计费的三类能力在 Dart 侧的调用面特征。
    ///
    /// ⚠️ 往这里加豁免之前先回 AD-3 改口径 —— 这个清单不是「让测试变绿」的开关。
    const forbidden = <String, String>{
      'google_maps_webservice': 'Places/Geocoding/Directions 的 Dart 客户端',
      'flutter_google_places': 'Places 自动补全',
      'google_places_flutter': 'Places 自动补全',
      'package:geocoding/': '地址与坐标互转（Geocoding）',
      'placesAutocomplete': 'Places 自动补全',
      'GoogleMapsPlaces': 'Places',
      'GoogleMapsGeocoding': 'Geocoding',
      'GoogleMapsDirections': 'Directions',
      'maps/api/place': 'Places REST',
      'maps/api/geocode': 'Geocoding REST',
      'maps/api/directions': 'Directions REST',
      'placemarkFromCoordinates': '反向地理编码',
      'locationFromAddress': '正向地理编码',
    };

    test('全仓源码里没有任何 Places / Geocoding / Directions 的调用面', () {
      final hits = <String>[];
      for (final f in dartFiles()) {
        final src = f.readAsStringSync();
        for (final entry in forbidden.entries) {
          if (src.contains(entry.key)) {
            hits.add('${f.path} 命中 "${entry.key}"（${entry.value}）');
          }
        }
      }
      expect(hits, isEmpty,
          reason: '🔴 越界即计费（AD-3 Rule 2）。这三类才是按次收钱的，而本项目'
              '「平台不做地理编码」（PRD ①）根本不需要它们。要加先回 AD-3 改口径。\n'
              '${hits.join("\n")}');
    });

    test('pubspec 里没有引入这三类的包', () {
      final pubspec = File('pubspec.yaml').readAsStringSync();
      // 只看依赖声明行（注释里提到这些词是在解释边界，不算违规）。
      final depLines = pubspec
          .split('\n')
          .where((l) => !l.trimLeft().startsWith('#'))
          .join('\n');
      for (final bad in [
        'geocoding:',
        'google_maps_webservice:',
        'flutter_google_places:',
        'google_places_flutter:',
      ]) {
        expect(depLines.contains(bad), isFalse, reason: '🔴 $bad 属按次计费能力（AD-3）');
      }
    });
  });

  group('🔴 AC5：地图只出现在两处', () {
    /// 允许出现 `GoogleMap` widget 的文件。
    ///
    /// - 选点弹层（Story 1.4）
    /// - 详情页的定位小地图（Story 1.5 —— 落地时把它加进来，**不是**把这条测试删掉）
    ///
    /// ⚠️ **列表页永远不在这个名单里**：那是 PRD ⑥ 明确排除的「地图浏览视图」。
    const allowed = <String>{
      'lib/features/place/presentation/place_map_picker_sheet.dart',
    };

    test('只有白名单里的文件用 GoogleMap widget', () {
      final offenders = <String>[];
      for (final f in dartFiles()) {
        final path = f.path.replaceAll(r'\', '/');
        if (allowed.contains(path)) continue;
        final src = f.readAsStringSync();
        // 认 import 而不是认类名：类名可能出现在注释里，而 import 是真的用了。
        if (src.contains("package:google_maps_flutter/")) {
          offenders.add(path);
        }
      }
      expect(offenders, isEmpty,
          reason: '🔴 地图只许出现在选点弹层与详情页小地图两处（AD-3 Rule 3 / AC5）。\n'
              '列表页嵌地图 = PRD ⑥ 明确排除的「地图浏览视图」。\n'
              '新增合法出口时把文件加进 allowed，**不要**删这条测试。\n'
              '${offenders.join("\n")}');
    });

    test('列表页确实没有引入地图', () {
      final list = File('lib/features/place/presentation/place_list_page.dart')
          .readAsStringSync();
      expect(list.contains('google_maps_flutter'), isFalse);
    });
  });

  group('🔴 AC2：仓库内无明文密钥', () {
    test('Android manifest 里只有占位符，不是真实密钥', () {
      final manifest =
          File('android/app/src/main/AndroidManifest.xml').readAsStringSync();
      expect(manifest.contains('com.google.android.geo.API_KEY'), isTrue,
          reason: '没有这条 meta-data 的话 Android 上地图根本不会渲染');
      expect(manifest.contains(r'${googleMapsApiKey}'), isTrue,
          reason: '必须是 manifest 占位符，真实值由 gradle 从 env / gitignored 文件注入');
      // Google API key 的典型形状：AIza + 35 个字符。出现即明文入库。
      expect(RegExp(r'AIza[0-9A-Za-z_\-]{30,}').hasMatch(manifest), isFalse,
          reason: '🔴 明文密钥入库（CLAUDE.md 护栏：凭证全部 env 注入，绝不入库）');
    });

    test('iOS Info.plist 里只有构建变量引用', () {
      final plist = File('ios/Runner/Info.plist').readAsStringSync();
      expect(plist.contains('<key>GMSApiKey</key>'), isTrue);
      expect(plist.contains(r'$(GOOGLE_MAPS_API_KEY)'), isTrue);
      expect(RegExp(r'AIza[0-9A-Za-z_\-]{30,}').hasMatch(plist), isFalse,
          reason: '🔴 明文密钥入库');
    });

    test('密钥模板文件存在且只放占位符', () {
      final androidExample = File('android/maps.properties.example');
      final iosExample = File('ios/Flutter/Maps.xcconfig.example');
      expect(androidExample.existsSync(), isTrue,
          reason: '没有模板，下一个人不知道该往哪儿放密钥');
      expect(iosExample.existsSync(), isTrue);
      for (final f in [androidExample, iosExample]) {
        final src = f.readAsStringSync();
        expect(src.contains('YOUR_'), isTrue, reason: '${f.path} 应该只有占位符');
        expect(RegExp(r'AIza[0-9A-Za-z_\-]{30,}').hasMatch(src), isFalse,
            reason: '🔴 ${f.path} 里有真实密钥');
      }
    });

    /// 🔴 **按「非注释的整行」比对，不是 `contains`**（code-review 2026-09-15）：
    /// 两个文件名在同一份 .gitignore 的**说明注释里也出现**，用 `contains` 的话
    /// 把真正那条忽略规则删掉，测试照样绿 —— 而密钥就这么进了仓库。
    test('真实密钥文件已 gitignored（按整行匹配，注释不算）', () {
      bool ignoresExactly(String gitignorePath, String pattern) {
        return File(gitignorePath)
            .readAsLinesSync()
            .map((l) => l.trim())
            .where((l) => l.isNotEmpty && !l.startsWith('#'))
            .contains(pattern);
      }

      expect(ignoresExactly('android/.gitignore', 'maps.properties'), isTrue,
          reason: '🔴 android/maps.properties 没被忽略 → 真实密钥会被 commit 进去');
      expect(ignoresExactly('ios/.gitignore', 'Flutter/Maps.xcconfig'), isTrue,
          reason: '🔴 ios/Flutter/Maps.xcconfig 没被忽略 → 真实密钥会被 commit 进去');
    });

    /// 模板文件反过来**必须不被忽略** —— 被忽略掉的话下一个人 clone 下来看不到它，
    /// 也就不知道密钥该放哪儿。
    test('模板文件没有被一起忽略', () {
      final androidIgnore = File('android/.gitignore')
          .readAsLinesSync()
          .map((l) => l.trim())
          .where((l) => l.isNotEmpty && !l.startsWith('#'));
      expect(androidIgnore.contains('maps.properties.example'), isFalse);
      expect(androidIgnore.contains('maps.properties*'), isFalse,
          reason: '通配会把 .example 模板一起忽略掉');
    });
  });
}
