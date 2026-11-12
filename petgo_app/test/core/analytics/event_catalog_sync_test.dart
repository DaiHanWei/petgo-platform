import 'dart:io';

import 'package:flutter_test/flutter_test.dart';

/// analytics-pipeline Story 2.6 · L0：**App 实际发出的事件名 ⊆ 后端事件清单**。
///
/// 读后端 `event-catalog.yaml`（Story 1.2，事件的唯一来源），与从 `lib/` **机器提取**的事件名
/// 比对：清单里没有的名字 → 测试失败并列出首次出现位置。改名、拼错、新增未登记，CI 当场发现，
/// 不再等看板空了才知道（未登记事件后端标 unknown_event、不进明细层）。
///
/// 🔴 只认机器提取的结果，**不手抄事件清单** —— 手抄清单会随代码漂移，测试恒绿、看板恒空。
///
/// 提取覆盖：
/// 1. `Analytics.capture('<字面量>'`（允许换行；生命周期事件名含空格）；
/// 2. 三元 `Analytics.capture(cond ? 'a' : 'b'` 两侧都取；
/// 3. 常量 `Analytics.capture(kXxx` / `Analytics.capture(AnalyticsEvents.xxx` → 在 `lib/` 里查
///    `const String <名> = '<值>'` / `static const <名> = '<值>'` 取值；
/// 4. 门面派生（[_derived]）：`capturePurchase` / `buttonTapped` / `screen` / `identifyUser` /
///    生命周期等不以字面量出现在调用处的事件，并断言门面源码里确实还在用这些名字。
///
/// 动态拼接的事件名（无法静态取值）只能进 [dynamicEventAllowlist]，且必须写理由。
void main() {
  late _Catalog catalog;
  late _Extraction extraction;

  setUpAll(() {
    catalog = _Catalog.load();
    extraction = _extract();
  });

  test('事件清单可读且解析正常（防解析静默失败恒绿）', () {
    expect(catalog.events.length, greaterThan(150),
        reason: '只解析出 ${catalog.events.length} 个事件 —— event-catalog.yaml 写法变了？'
            '（约定：事件首行 `  - name: <事件名>`，属性单行 `      - { name: ... }`）');
    final noStatus = catalog.events.entries.where((e) => e.value == null).map((e) => e.key);
    expect(noStatus, isEmpty, reason: '这些事件没解析到 status：$noStatus');
    for (final s in catalog.events.values) {
      expect(const {'ACTIVE', 'PLANNED', 'RETIRED'}, contains(s));
    }
    // 生命周期事件带引号、含空格；属性行（同样以 `- { name:` 开头）不得被当成事件。
    expect(catalog.events, contains('Application Opened'));
    expect(catalog.events, isNot(contains('version')));
  });

  test('lib/ 中无法静态取值的事件名都已列入白名单', () {
    final unresolved = extraction.unresolved
        .where((u) => !dynamicEventAllowlist.containsKey(u.allowlistKey))
        .toList();
    expect(unresolved, isEmpty,
        reason: '以下 Analytics.capture 调用的事件名无法静态解析：\n'
            '${unresolved.map((u) => '  - ${u.location}  首参数：${u.arg}').join('\n')}\n'
            '改用字符串字面量或事件名常量（`const String x = \'...\'`）；确属动态拼接时，'
            '在本测试 dynamicEventAllowlist 里登记「${unresolved.isEmpty ? '' : unresolved.first.allowlistKey}」并写理由。');
  });

  test('白名单条目都还有效（防过期条目掩护新问题）', () {
    final live = extraction.unresolved.map((u) => u.allowlistKey).toSet();
    final stale = dynamicEventAllowlist.keys.where((k) => !live.contains(k)).toList();
    expect(stale, isEmpty, reason: '白名单条目已无对应调用，请删除：$stale');
    for (final reason in dynamicEventAllowlist.values) {
      expect(reason.trim(), isNotEmpty, reason: '白名单条目必须写理由');
    }
  });

  test('App 发出的每个事件名都已在 event-catalog.yaml 登记', () {
    final missing = extraction.names.keys
        .where((n) => !catalog.events.containsKey(n))
        .toList()
      ..sort();
    expect(missing, isEmpty,
        reason: '以下事件名不在 event-catalog.yaml 中：\n'
            '${missing.map((n) => '  - $n  （${extraction.names[n]!.first}）').join('\n')}\n'
            '固定三步：在 petgo-backend/src/main/resources/analytics/event-catalog.yaml 登记 '
            '→ 加 Dart 事件常量 → 本测试变绿。');
  });

  test('App 不再发出已下线（RETIRED）的事件', () {
    final retired = extraction.names.keys
        .where((n) => catalog.events[n] == 'RETIRED')
        .toList()
      ..sort();
    expect(retired, isEmpty,
        reason: '以下事件在清单中已是 RETIRED，代码却还在发（死埋点）：\n'
            '${retired.map((n) => '  - $n  （${extraction.names[n]!.first}）').join('\n')}');
  });

  test('门面派生表与门面源码一致（防门面改名后派生表过期）', () {
    for (final d in _derived) {
      final src = _stripLineComments(File(d.facadeFile).readAsStringSync());
      final re = RegExp(r'capture\(\s*' + RegExp.escape(d.facadeArg));
      expect(re.hasMatch(src), isTrue,
          reason: '${d.facadeFile} 里找不到 `capture(${d.facadeArg}` —— '
              '门面改了事件名？同步更新本测试的 _derived 表');
      if (!d.facadeArg.startsWith("'")) {
        expect(extraction.constants[d.facadeArg], d.event,
            reason: '常量 ${d.facadeArg} 的值应为 ${d.event}');
      }
      expect(extraction.derivedUsed, contains(d.event),
          reason: 'lib/ 里已没有 `${d.usageMarker}` 调用 —— 派生表条目过期，删掉或更新');
    }
  });

  test('自检：字面量 / 三元 / 常量 / 派生四条提取路径都在工作', () {
    const expected = {
      // analytics-pipeline Story 3.3 把 sku_picker_sheet 的加购 / 立即购买三元式拆成两个字面量调用。
      'toko_add_to_cart_tapped': _Via.literal,
      'toko_buy_now_tapped': _Via.literal,
      'user_followed': _Via.ternary,
      'user_unfollowed': _Via.ternary,
      'keepsake_card_generated': _Via.constant,
      'af_purchase': _Via.derived,
      'screen_viewed': _Via.derived,
      'Application Opened': _Via.constant,
      'signup_succeeded': _Via.literal,
    };
    expected.forEach((name, via) {
      expect(extraction.names, contains(name), reason: '未提取到 $name');
      expect(extraction.via[name], contains(via), reason: '$name 应经 $via 路径提取到');
    });
  });
}

/// 动态拼接的事件名白名单：键 = `<lib 相对路径>::<首参数源码>`，值 = 理由（必填）。
///
/// 注意：`'${tabName}_page'` / `'${tab.analyticsName}_page'` 是 `Analytics.screen()` 的
/// **页面名**（`screen_viewed` 的 `screen` 属性），不是事件名，不进白名单。当前 `lib/` 中没有
/// 动态拼接的事件名，初始为空；新增条目必须写理由。
const dynamicEventAllowlist = <String, String>{};

const _catalogPath = '../petgo-backend/src/main/resources/analytics/event-catalog.yaml';

/// 门面文件：除 `Analytics.capture(` 外，其内部裸 `capture(` 调用也提取。
const _facadeFile = 'lib/core/analytics/analytics.dart';

/// 门面派生：调用处不出现事件名字面量，由门面方法内部决定事件名。
const _derived = <_Derived>[
  _Derived('af_purchase', 'lib/core/analytics/analytics.dart', "'af_purchase'",
      'Analytics.capturePurchase('),
  _Derived('signup_succeeded', 'lib/core/analytics/analytics.dart', "'signup_succeeded'",
      'Analytics.captureSignupSucceeded('),
  _Derived('button_tapped', 'lib/core/analytics/analytics.dart', "'button_tapped'",
      'Analytics.buttonTapped('),
  // 手工补报 + 路由观察者（AnalyticsRouteObserver 内部同样调 Analytics.screen）。
  _Derived('screen_viewed', 'lib/core/analytics/analytics.dart', 'AnalyticsEvents.screenViewed',
      'Analytics.screen('),
  _Derived('user_identified', 'lib/core/analytics/analytics.dart', 'AnalyticsEvents.userIdentified',
      'Analytics.identifyUser('),
  // 生命周期：Analytics.init() → LifecycleEvents.start()。
  _Derived('Application Installed', 'lib/core/analytics/lifecycle_events.dart',
      'AnalyticsEvents.applicationInstalled', 'LifecycleEvents.start('),
  _Derived('Application Updated', 'lib/core/analytics/lifecycle_events.dart',
      'AnalyticsEvents.applicationUpdated', 'LifecycleEvents.start('),
  _Derived('Application Opened', 'lib/core/analytics/lifecycle_events.dart',
      'AnalyticsEvents.applicationOpened', 'LifecycleEvents.start('),
];

class _Derived {
  const _Derived(this.event, this.facadeFile, this.facadeArg, this.usageMarker);

  final String event;

  /// 门面所在文件（相对 `petgo_app/`）。
  final String facadeFile;

  /// 门面内 `capture(` 的首参数源码：字面量（带引号）或常量名。
  final String facadeArg;

  /// `lib/` 中出现即视为该事件在用的调用标记。
  final String usageMarker;
}

enum _Via { literal, ternary, constant, derived }

class _Catalog {
  _Catalog(this.events);

  /// 事件名 → status（解析不到为 null）。
  final Map<String, String?> events;

  /// 按 Story 1.2 定的结构逐行解析（不引 yaml 依赖）：
  /// `events:` 之下每个条目首行 `  - name: <事件名>`（可能带引号），本条目内 `    status: X`；
  /// 属性行 `      - { name: ..., ... }` 缩进更深，不匹配首行正则。
  static _Catalog load() {
    final file = File(_catalogPath);
    if (!file.existsSync()) {
      fail('找不到 $_catalogPath —— Story 1.2（事件清单）未合入？缺清单时不能放行，比对测试就此失去守门意义。');
    }
    final header = RegExp(r'^  - name:\s*(.+?)\s*$');
    final status = RegExp(r'^    status:\s*([A-Z]+)\s*$');
    final events = <String, String?>{};
    var inEvents = false;
    String? current;
    for (final line in file.readAsLinesSync()) {
      if (line.startsWith('events:')) {
        inEvents = true;
        continue;
      }
      if (!inEvents) continue;
      // 回到顶层的其他键 → events 段结束。
      if (line.isNotEmpty && !line.startsWith(' ') && !line.startsWith('#')) break;
      final h = header.firstMatch(line);
      if (h != null) {
        current = _unquote(h.group(1)!);
        expect(events.containsKey(current), isFalse, reason: '清单里事件名重复：$current');
        events[current] = null;
        continue;
      }
      final s = status.firstMatch(line);
      if (s != null && current != null) events[current] = s.group(1);
    }
    return _Catalog(events);
  }

  static String _unquote(String v) {
    if (v.length >= 2 &&
        ((v.startsWith('"') && v.endsWith('"')) || (v.startsWith("'") && v.endsWith("'")))) {
      return v.substring(1, v.length - 1);
    }
    return v;
  }
}

class _Unresolved {
  _Unresolved(this.file, this.line, this.arg);

  final String file;
  final int line;
  final String arg;

  String get location => '$file:$line';
  String get allowlistKey => '$file::$arg';
}

class _Extraction {
  /// 事件名 → 出现位置（`文件:行号`，按扫描顺序）。
  final names = <String, List<String>>{};

  /// 事件名 → 经哪些路径提取到（自检用）。
  final via = <String, Set<_Via>>{};

  /// 常量名（`Name` 与 `Class.Name` 两种写法）→ 值；同名不同值的不收（视为无法解析）。
  final constants = <String, String>{};

  /// lib/ 里确有调用的门面派生事件。
  final derivedUsed = <String>{};

  final unresolved = <_Unresolved>[];

  void add(String name, String location, _Via how) {
    names.putIfAbsent(name, () => []).add(location);
    via.putIfAbsent(name, () => {}).add(how);
  }
}

/// 整行注释（`//` / `///` 开头）替换为空行：注释里的示例调用不算、且保持行号不变。
String _stripLineComments(String src) => src
    .split('\n')
    .map((l) => l.trimLeft().startsWith('//') ? '' : l)
    .join('\n');

int _lineOf(String src, int offset) => '\n'.allMatches(src.substring(0, offset)).length + 1;

_Extraction _extract() {
  final out = _Extraction();
  final sources = <String, String>{};
  final files = Directory('lib')
      .listSync(recursive: true)
      .whereType<File>()
      .where((f) => f.path.endsWith('.dart'))
      .toList()
    ..sort((a, b) => a.path.compareTo(b.path));
  for (final f in files) {
    sources[f.path.replaceAll(r'\', '/')] = _stripLineComments(f.readAsStringSync());
  }

  // ① 收集事件名候选常量：顶层 `const String x = '...'` 与类内 `static const [String] x = '...'`。
  final constRe = RegExp(
      r"""(?:^|\s)(static\s+)?const\s+(?:String\s+)?([A-Za-z_$][\w$]*)\s*=\s*'([^'$\\]*)'\s*;""",
      multiLine: true);
  final classRe = RegExp(r'\bclass\s+([A-Za-z_$][\w$]*)');
  final seen = <String, Set<String>>{};
  sources.forEach((path, src) {
    final classes = classRe.allMatches(src).toList();
    for (final m in constRe.allMatches(src)) {
      final name = m.group(2)!;
      final value = m.group(3)!;
      seen.putIfAbsent(name, () => {}).add(value);
      if (m.group(1) != null) {
        final owners = classes.where((c) => c.start < m.start);
        if (owners.isNotEmpty) {
          seen.putIfAbsent('${owners.last.group(1)}.$name', () => {}).add(value);
        }
      }
    }
  });
  seen.forEach((k, v) {
    if (v.length == 1) out.constants[k] = v.single;
  });

  // ② 提取 `Analytics.capture(` 的首参数。
  final callRe = RegExp(r'\bAnalytics\.capture\(\s*');
  final ternaryRe = RegExp(r"""^[^,()]+?\?\s*'([^'$\\]+)'\s*:\s*'([^'$\\]+)'""");
  final literalRe = RegExp(r"""^'([^'$\\]+)'\s*[,)]""");
  final identRe = RegExp(r'^([A-Za-z_$][\w$]*(?:\.[A-Za-z_$][\w$]*)?)\s*[,)]');
  final argRe = RegExp(r'^[^,)\n]*');
  // 门面内部的裸调用 `capture(...)`（screen / buttonTapped / identifyUser 等）同样要机器提取，
  // 防门面新增方法时只靠手写的 _derived 表而漏扫；跳过 `capture(` 的定义行本身。
  final bareRe = RegExp(r'(?<![\w.$])capture\(\s*');
  Iterable<RegExpMatch> callsIn(String path, String src) sync* {
    yield* callRe.allMatches(src);
    if (path != _facadeFile) return;
    for (final m in bareRe.allMatches(src)) {
      final lineStart = src.lastIndexOf('\n', m.start) + 1;
      if (src.substring(lineStart, m.start).contains('Future<void>')) continue;
      yield m;
    }
  }

  sources.forEach((path, src) {
    for (final m in callsIn(path, src)) {
      final rest = src.substring(m.end);
      final loc = '$path:${_lineOf(src, m.start)}';
      final t = ternaryRe.firstMatch(rest);
      if (t != null) {
        out.add(t.group(1)!, loc, _Via.ternary);
        out.add(t.group(2)!, loc, _Via.ternary);
        continue;
      }
      final l = literalRe.firstMatch(rest);
      if (l != null) {
        out.add(l.group(1)!, loc, _Via.literal);
        continue;
      }
      final i = identRe.firstMatch(rest);
      final value = i == null ? null : out.constants[i.group(1)!];
      if (value != null) {
        out.add(value, loc, _Via.constant);
        continue;
      }
      out.unresolved.add(_Unresolved(path, _lineOf(src, m.start), argRe.firstMatch(rest)!.group(0)!.trim()));
    }
  });

  // ③ 门面派生：调用标记在 lib/ 出现即计入，位置取首次出现处。
  for (final d in _derived) {
    for (final e in sources.entries) {
      final idx = e.value.indexOf(d.usageMarker);
      if (idx < 0) continue;
      out.add(d.event, '${e.key}:${_lineOf(e.value, idx)}（经 ${d.usageMarker}…）', _Via.derived);
      out.derivedUsed.add(d.event);
      break;
    }
  }
  return out;
}
