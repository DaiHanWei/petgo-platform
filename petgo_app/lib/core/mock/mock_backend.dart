import 'package:dio/dio.dart';
import 'package:flutter/foundation.dart';

/// 内存假后端（Mock 模式核心）。
///
/// 单例,随 APP 进程生命周期存活 → **重启即重置回初始种子**(创建项消失,符合需求)。
/// 按 `(method, path)` 解析请求,返回与各 model `fromJson` 匹配的 JSON;
/// 写入类端点改内存态,后续读端点反映变更(发帖/评论/点赞/改昵称/改状态/分诊/会话/通知已读…)。
class MockBackend {
  MockBackend._() {
    _seed();
  }
  static final MockBackend instance = MockBackend._();

  int _id = 1000;
  int _nextId() => ++_id;

  static String _iso(Duration ago) =>
      DateTime.now().toUtc().subtract(ago).toIso8601String();

  /// yyyy-MM-dd（事件日期，F9）。
  static String _date(Duration ago) {
    final d = DateTime.now().toUtc().subtract(ago);
    return '${d.year}-${d.month.toString().padLeft(2, '0')}-${d.day.toString().padLeft(2, '0')}';
  }

  // ---- 内存态 ----
  late Map<String, dynamic> _profile;
  final List<Map<String, dynamic>> _feed = [];
  final Map<int, List<Map<String, dynamic>>> _comments = {};
  final List<Map<String, dynamic>> _notifications = [];
  final List<Map<String, dynamic>> _consultHistory = [];
  final List<Map<String, dynamic>> _timeline = [];
  Map<String, dynamic>? _petProfile;
  final Map<int, String> _triageLevel = {}; // triageId → GREEN/YELLOW/RED
  final List<String> _triageCycle = ['GREEN', 'YELLOW', 'RED'];
  int _triageSeq = 0;
  Map<String, dynamic>? _activeSession;

  void _seed() {
    _profile = {
      'id': 1,
      'nickname': '测试用户',
      'displayName': '测试用户',
      'email': 'demo@petgo.app',
      'avatarUrl': null,
      'petStatus': 'HAS_PET', // 有宠物 → 解锁成长档案
      'onboardingCompleted': true,
      'hasPetProfile': true,
      'role': 'USER',
    };

    // 假数据：贴合 10 张真实宠物照片的文案 + asset 封面图（部分多图供详情轮播）。
    // 图片走 `asset:` 前缀，经 AppImage 解析为打包资源；Feed 卡片 + 详情页均渲染真图。
    const a = 'asset:assets/seed/';
    final samples = <Map<String, dynamic>>[
      {'type': 'DAILY', 'body': 'Oyen 又跑屋顶看夕阳了，金色的毛真好看 🐱', 'nick': 'Putri', 'like': 124,
        'imgs': ['${a}pet01.jpg', '${a}pet08.jpg']},
      {'type': 'DAILY', 'body': '换上新项圈，眼睛瞪得像铜铃 😺', 'nick': 'Sari', 'like': 88,
        'imgs': ['${a}pet07.jpg']},
      {'type': 'GROWTH_MOMENT', 'body': '你们快看！它身上自带一颗爱心 🩶', 'nick': null, 'like': 256,
        'imgs': ['${a}pet02.jpg', '${a}pet07.jpg']},
      {'type': 'DAILY', 'body': '金毛弟弟下楼遛弯，捡球捡到飞起 🐕', 'nick': 'Budi', 'like': 73,
        'imgs': ['${a}pet08.jpg']},
      {'type': 'DAILY', 'body': '缅因主子的霸气侧颜，气场两米八 😼', 'nick': 'Andi', 'like': 61,
        'imgs': ['${a}pet03.jpg']},
      {'type': 'DAILY', 'body': '两位主子的迷惑同框，下面那只到底在想啥呢 😹', 'nick': 'Maya', 'like': 142,
        'imgs': ['${a}pet04.jpg', '${a}pet03.jpg', '${a}pet05.jpg']},
      {'type': 'DAILY', 'body': '深夜偷看猫咪迷之舞步，这是要出道？💃', 'nick': 'Dewi', 'like': 99,
        'imgs': ['${a}pet05.jpg']},
      {'type': 'GROWTH_MOMENT', 'body': '洗香香时间到！金毛变身泡泡怪 🛁', 'nick': null, 'like': 47,
        'imgs': ['${a}pet10.jpg']},
      {'type': 'DAILY', 'body': '给牛头梗戴上波波头，文艺范儿瞬间拿捏 💇', 'nick': 'Joko', 'like': 210,
        'imgs': ['${a}pet09.jpg']},
      {'type': 'KNOWLEDGE', 'body': '给家里两只画了张「相爱相杀」图，附多猫家庭和谐相处指南 🎨', 'nick': 'Rina', 'like': 35,
        'imgs': ['${a}pet06.jpg']},
    ];
    for (var i = 0; i < samples.length; i++) {
      final s = samples[i];
      _feed.add(_post(
        id: 100 + i,
        type: s['type'] as String,
        body: s['body'] as String?,
        nickname: s['nick'] as String?,
        likeCount: s['like'] as int,
        images: (s['imgs'] as List).cast<String>(),
        ago: Duration(hours: i * 5 + 1),
      ));
    }
    // 详情页评论种子(给第一条帖)
    _comments[100] = [
      _comment(id: 900, nickname: 'Rina', body: '太可爱了吧！😍', ago: const Duration(hours: 1)),
      _comment(id: 901, nickname: 'Joko', body: '同款主子,哈哈', ago: const Duration(hours: 2)),
    ];

    _notifications.addAll([
      // type 必须是后端 NotificationType 合法值（VET_REPLY/CONSULT_CLOSED/CONTENT_LIKED/CONTENT_COMMENTED/NEW_CONSULT_REQUEST）。
      _notif(token: 'n1', type: 'CONTENT_COMMENTED', title: '新评论', body: 'Rina 评论了你的帖子', read: false, ago: const Duration(minutes: 20)),
      _notif(token: 'n2', type: 'CONTENT_LIKED', title: '新的赞', body: 'Budi 赞了你的帖子', read: false, ago: const Duration(hours: 3)),
      _notif(token: 'n3', type: 'VET_REPLY', title: '问诊更新', body: '兽医已回复你的咨询', read: true, ago: const Duration(days: 1)),
    ]);

    _consultHistory.addAll([
      {
        'type': 'AI', 'date': _iso(const Duration(hours: 6)), 'triageId': 5001,
        'dangerLevel': 'GREEN', 'symptomSummary': '偶尔打喷嚏,精神食欲都正常',
      },
      {
        'type': 'AI', 'date': _iso(const Duration(days: 1)), 'triageId': 5002,
        'dangerLevel': 'RED', 'symptomSummary': '误食巧克力,现在呕吐',
      },
      {
        'type': 'VET', 'date': _iso(const Duration(days: 2)), 'sessionId': 6001,
        'vetDisplayName': 'Drh. Andi', 'sessionSummary': '猫咪发热,建议观察并补水',
        'userStars': 5, 'archived': true, 'terminalState': 'CLOSED',
      },
      {
        'type': 'VET', 'date': _iso(const Duration(days: 4)), 'sessionId': 6002,
        'vetDisplayName': 'Drh. Maya', 'sessionSummary': null,
        'userStars': null, 'archived': false, 'terminalState': 'CLOSED',
      },
    ]);

    _petProfile = {
      'id': 7001, 'name': 'Oyen', 'cardToken': 'mock-card-token-oyen',
      'petType': 'CAT', 'avatarUrl': 'asset:assets/seed/pet01.jpg', 'breed': '橘猫', 'birthday': '2022-05-01',
      'intro': '爱睡觉、爱晒太阳的小橘', 'createdAt': _iso(const Duration(days: 200)),
    };

    _timeline.addAll([
      {'kind': 'HAPPY_MOMENT', 'date': _iso(const Duration(days: 1)), 'eventDate': _date(const Duration(days: 1)), 'postId': 101, 'imageUrls': ['asset:assets/seed/pet01.jpg'], 'text': 'Oyen 屋顶看夕阳'},
      {'kind': 'HEALTH_EVENT', 'date': _iso(const Duration(days: 3)), 'postId': null, 'imageUrls': [], 'text': '误食巧克力,已就医', 'aiLevel': 'RED', 'symptomSummary': '呕吐'},
      {'kind': 'HAPPY_MOMENT', 'date': _iso(const Duration(days: 7)), 'eventDate': _date(const Duration(days: 7)), 'postId': 105, 'imageUrls': ['asset:assets/seed/pet02.jpg'], 'text': '发现身上的爱心斑纹'},
    ]);
  }

  Map<String, dynamic> _post({
    required int id, required String type, String? body, String? nickname,
    int likeCount = 0, String? imageUrl, List<String>? images, required Duration ago,
  }) {
    final imgs = images ?? (imageUrl == null ? const <String>[] : [imageUrl]);
    return {
      'id': id, 'authorId': 1, 'authorDeleted': false,
      'authorNickname': nickname ?? '测试用户', 'authorAvatarUrl': null,
      'type': type, 'body': body,
      'firstImageUrl': imgs.isEmpty ? null : imgs.first,
      'imageUrls': imgs, // 详情页轮播全图
      'likeCount': likeCount, 'createdAt': _iso(ago),
    };
  }

  Map<String, dynamic> _comment({
    required int id, required String nickname, required String body, required Duration ago,
  }) => {
        'id': id, 'authorId': 2, 'authorDeleted': false,
        'authorNickname': nickname, 'authorAvatarUrl': null,
        'body': body, 'createdAt': _iso(ago), 'replyCount': 0, 'replies': <dynamic>[],
      };

  Map<String, dynamic> _notif({
    required String token, required String type, String? title, String? body,
    required bool read, required Duration ago,
  }) => {
        'type': type, 'title': title, 'body': body,
        'deepLinkType': null, 'deepLinkToken': null, 'read': read, 'createdAt': _iso(ago),
      }..['_token'] = token;

  Map<String, dynamic> _envelope(List<Map<String, dynamic>> items) =>
      {'items': items, 'nextCursor': null, 'hasMore': false};

  // ---- 主入口：返回 Response 或抛 DioException(404) ----
  Response<dynamic>? handle(RequestOptions o) {
    final m = o.method.toUpperCase();
    final p = o.path.replaceFirst(RegExp(r'\?.*$'), '');
    final q = o.queryParameters;
    final body = o.data is Map ? (o.data as Map).cast<String, dynamic>() : <String, dynamic>{};

    Response<dynamic> ok([dynamic data]) =>
        Response<dynamic>(requestOptions: o, statusCode: 200, data: data);
    Response<dynamic> noContent() =>
        Response<dynamic>(requestOptions: o, statusCode: 204, data: null);

    // ---------- AUTH / ME ----------
    if (m == 'POST' && p.endsWith('/auth/google')) {
      return ok({
        'accessToken': 'mock-access', 'refreshToken': 'mock-refresh',
        'role': 'USER', 'isNewUser': false, 'onboardingCompleted': true,
        'profile': _profile,
      });
    }
    if (m == 'POST' && p.endsWith('/auth/refresh')) {
      return ok({'accessToken': 'mock-access2', 'refreshToken': 'mock-refresh2'});
    }
    if (m == 'POST' && (p.endsWith('/auth/logout') || p.endsWith('/vet/logout'))) return noContent();
    if (m == 'POST' && p.endsWith('/auth/vet/login')) {
      return ok({'accessToken': 'mock-vet', 'refreshToken': 'mock-vet-r', 'displayName': 'Drh. Demo', 'role': 'VET'});
    }
    // 精确匹配用户 /api/v1/me(用 /v1/me 收尾,避免误吃 /pet-profiles/me、/vet/me)。
    if (p.endsWith('/v1/me') && m == 'GET') return ok(_profile);
    if (p.endsWith('/v1/me') && m == 'PATCH') {
      if (body['nickname'] != null) _profile['nickname'] = body['nickname'];
      if (body['petStatus'] != null) _profile['petStatus'] = body['petStatus'];
      return ok(_profile);
    }
    if (p.endsWith('/v1/me') && m == 'DELETE') return Response(requestOptions: o, statusCode: 202);
    if (p.endsWith('/vet/me') && m == 'GET') return ok({'id': 1, 'displayName': 'Drh. Demo', 'status': 'ACTIVE'});

    // ---------- CONTENT / FEED ----------
    if (m == 'GET' && p.endsWith('/me/posts')) {
      return ok(_envelope(_feed
          .where((e) => e['authorId'] == 1)
          .map((e) => {'id': e['id'], 'type': e['type'], 'body': e['body'], 'firstImageUrl': e['firstImageUrl']})
          .toList()));
    }
    if (m == 'GET' && p.endsWith('/content-posts')) {
      final cat = (q['category'] ?? 'ALL') as String;
      final items = cat == 'ALL' ? _feed : _feed.where((e) => e['type'] == cat).toList();
      // 投影为卡片 10 字段契约：内部 imageUrls（详情多图）不进 Feed 信封。
      return ok(_envelope(items.map((e) {
        final card = Map<String, dynamic>.from(e)..remove('imageUrls');
        return card;
      }).toList()));
    }
    if (m == 'POST' && p.endsWith('/content-posts')) {
      // 镜像后端发布时三方自动审核（AC8 · F10）：占位关键词/图标记命中 → 422，不落库。
      final text = body['text'] as String?;
      final imgs = (body['imageUrls'] as List?)?.cast<dynamic>() ?? const [];
      // 占位敏感词（印尼语/英语，与后端 ContentModerationService 对齐）；真实三方后接。
      const blockedWords = ['judi', 'narkoba', 'pornografi', 'penipuan', 'gambling', 'scam'];
      final lower = (text ?? '').toLowerCase();
      if (blockedWords.any(lower.contains)) {
        throw _problem(o, 422, 'content-text-blocked', 'Konten mengandung kata tidak pantas');
      }
      if (imgs.any((u) => u.toString().contains('moderation-blocked'))) {
        throw _problem(o, 422, 'content-image-blocked', 'Gambar mengandung konten terlarang');
      }
      final id = _nextId();
      // 发布回灌：把刚选/拍的图（mock 上传返回的 file: URL）带进新帖，Feed/详情即时显示真图。
      _feed.insert(0, _post(id: id, type: (body['type'] ?? 'DAILY') as String,
          body: text, likeCount: 0, images: imgs.map((e) => e.toString()).toList(),
          ago: Duration.zero));
      return ok({'id': id});
    }
    final detailMatch = RegExp(r'/content-posts/(\d+)$').firstMatch(p);
    if (detailMatch != null && m == 'GET') {
      final id = int.parse(detailMatch.group(1)!);
      final post = _feed.firstWhere((e) => e['id'] == id, orElse: () => <String, dynamic>{});
      if (post.isEmpty) throw _notFound(o);
      return ok({
        ...post, 'commentCount': (_comments[id]?.length ?? 0), 'liked': false,
        'isAuthor': post['authorId'] == 1,
        // 详情轮播取全图列表（多图帖展示多张）；回退 firstImageUrl。
        'imageUrls': (post['imageUrls'] as List?)?.isNotEmpty == true
            ? post['imageUrls']
            : (post['firstImageUrl'] == null ? [] : [post['firstImageUrl']]),
      });
    }
    if (detailMatch != null && m == 'DELETE') {
      _feed.removeWhere((e) => e['id'] == int.parse(detailMatch.group(1)!));
      return ok();
    }
    final commentsMatch = RegExp(r'/content-posts/(\d+)/comments$').firstMatch(p);
    if (commentsMatch != null) {
      final id = int.parse(commentsMatch.group(1)!);
      if (m == 'GET') return ok(_envelope(_comments[id] ?? []));
      if (m == 'POST') {
        final c = _comment(id: _nextId(), nickname: _profile['nickname'] as String,
            body: (body['body'] ?? '') as String, ago: Duration.zero);
        (_comments[id] ??= []).insert(0, c);
        return ok(c);
      }
    }
    final repliesMatch = RegExp(r'/comments/(\d+)/replies$').firstMatch(p);
    if (repliesMatch != null) {
      if (m == 'GET') return ok(_envelope([]));
      if (m == 'POST') {
        return ok({'id': _nextId(), 'authorId': 1, 'authorDeleted': false,
          'authorNickname': _profile['nickname'], 'authorAvatarUrl': null,
          'body': body['body'] ?? '', 'createdAt': _iso(Duration.zero), 'replyCount': null, 'replies': null});
      }
    }
    if (RegExp(r'/comments/(\d+)$').hasMatch(p) && m == 'DELETE') return ok();
    final likeMatch = RegExp(r'/content-posts/(\d+)/like$').firstMatch(p);
    if (likeMatch != null) {
      final id = int.parse(likeMatch.group(1)!);
      final post = _feed.firstWhere((e) => e['id'] == id, orElse: () => <String, dynamic>{});
      final liked = m == 'POST';
      if (post.isNotEmpty) post['likeCount'] = (post['likeCount'] as int) + (liked ? 1 : -1);
      return ok({'liked': liked, 'likeCount': post['likeCount'] ?? 0});
    }
    if (RegExp(r'/content-posts/(\d+)/reports$').hasMatch(p)) return ok();
    if (RegExp(r'/users/(\d+)/mini-profile$').hasMatch(p)) {
      return ok({'postCount': 6, 'isDeactivated': false, 'nickname': '测试用户', 'avatarUrl': null});
    }

    // ---------- PET PROFILE / TIMELINE / HEALTH ----------
    if (p.endsWith('/pet-profiles/me/timeline') && m == 'GET') return ok(_envelope(_timeline));
    if (p.endsWith('/pet-profiles/me/archive-stats') && m == 'GET') {
      if (_petProfile == null) throw _notFound(o);
      final happy = _timeline.where((e) => e['kind'] == 'HAPPY_MOMENT').length;
      final consult = _timeline.where((e) => e['kind'] == 'HEALTH_EVENT').length;
      final type = _petProfile!['petType'] as String?;
      return ok({
        'happyMomentCount': happy, 'consultCount': consult,
        'milestoneCompleted': 0, 'milestoneTotal': (type == 'CAT' || type == 'DOG') ? 30 : 15,
      });
    }
    if (p.endsWith('/pet-profiles/me/calendar') && m == 'GET') {
      if (_petProfile == null) throw _notFound(o);
      final year = int.tryParse('${q['year']}') ?? DateTime.now().year;
      final month = int.tryParse('${q['month']}') ?? DateTime.now().month;
      return ok(_calendarFor(year, month));
    }
    if (p.endsWith('/pet-profiles/me/day') && m == 'GET') {
      if (_petProfile == null) throw _notFound(o);
      final date = (q['date'] ?? '') as String;
      final items = _timeline.where((e) => _itemDateKey(e) == date).toList()
        ..sort((a, b) => (a['date'] as String).compareTo(b['date'] as String)); // created_at 正序
      return ok({'date': date, 'items': items});
    }
    if (p.endsWith('/pet-profiles/me') && m == 'GET') {
      if (_petProfile == null) throw _notFound(o);
      return ok(_petProfile);
    }
    if (p.endsWith('/pet-profiles/me') && m == 'PATCH') {
      _petProfile = {...?_petProfile, ...body};
      return ok(_petProfile);
    }
    if (p.endsWith('/pet-profiles') && m == 'POST') {
      _petProfile = {'id': _nextId(), 'cardToken': 'mock-card-${_nextId()}', ...body, 'createdAt': _iso(Duration.zero)};
      _profile['hasPetProfile'] = true;
      return ok(_petProfile);
    }
    if (p.endsWith('/health-events/archive-decisions') && m == 'POST') {
      if (body['decision'] == 'ARCHIVED') {
        _timeline.insert(0, {'kind': 'HEALTH_EVENT', 'date': _iso(Duration.zero), 'postId': null,
          'imageUrls': [], 'text': body['symptomSummary'] ?? '健康事件', 'aiLevel': body['aiLevel'], 'symptomSummary': body['symptomSummary']});
      }
      return ok();
    }
    if (p.endsWith('/health-events/decision') && m == 'GET') return ok({'decided': false});

    // ---------- AI 分诊 ----------
    if (p.endsWith('/triage') && m == 'POST') {
      final id = _nextId();
      _triageLevel[id] = _triageCycle[_triageSeq++ % _triageCycle.length]; // 轮流绿/黄/红
      return Response(requestOptions: o, statusCode: 202, data: {'triageId': id});
    }
    final triageGet = RegExp(r'/triage/(\d+)$').firstMatch(p);
    if (triageGet != null && m == 'GET') {
      final id = int.parse(triageGet.group(1)!);
      final lvl = _triageLevel[id] ?? 'GREEN';
      return ok({
        'status': 'DONE', 'dangerLevel': lvl,
        'advice': lvl == 'RED' ? '请立即就医' : (lvl == 'YELLOW' ? '建议尽快观察并咨询兽医' : '暂无明显风险,保持观察'),
        'medicationRef': null, 'disclaimer': '本结果仅供参考,不替代专业兽医诊断。',
        'observation': {'indicators': ['精神状态', '食欲', '排泄'], 'timeWindow': '24 小时', 'escalationTriggers': ['持续呕吐', '精神萎靡']},
      });
    }

    // ---------- 兽医咨询(用户侧) ----------
    // expectedWindow 是后端文案 key（前端映射 l10n），非渲染文本 —— 镜像 ConsultAvailabilityResponse.DEFAULT_WINDOW_KEY。
    if (p.endsWith('/consult/availability') && m == 'GET') return ok({'vetOnline': true, 'expectedWindow': 'WEEKDAY_8_23'});
    if (p.endsWith('/consult-sessions/active') && m == 'GET') {
      return _activeSession == null ? noContent() : ok(_activeSession);
    }
    if (p.endsWith('/consult-sessions/pending-rating') && m == 'GET') return noContent();
    if (p.endsWith('/consult-sessions') && m == 'POST') {
      _activeSession = {'id': _nextId(), 'status': 'WAITING', 'source': body['source'] ?? 'DIRECT',
        'vetId': null, 'waitingElapsedSeconds': 0, 'timedOut': false, 'alreadyActive': false,
        'closedReason': null, 'interruptedReason': null};
      return ok(_activeSession);
    }
    // 用户侧 consult-sessions/{id}:用 !/vet/ 守卫,避免误吞兽医侧 /vet/consult-sessions/{id}。
    final sessGet = RegExp(r'/consult-sessions/(\d+)$').firstMatch(p);
    if (sessGet != null && m == 'GET' && !p.contains('/vet/')) {
      return ok(_activeSession ?? {'id': int.parse(sessGet.group(1)!), 'status': 'IN_PROGRESS', 'source': 'DIRECT',
        'waitingElapsedSeconds': 0, 'timedOut': false, 'alreadyActive': false});
    }
    if (RegExp(r'/consult-sessions/(\d+)/continue-waiting$').hasMatch(p)) {
      _activeSession?['waitingElapsedSeconds'] = 0;
      return ok(_activeSession ?? {});
    }
    if (RegExp(r'/consult-sessions/(\d+)$').hasMatch(p) && m == 'DELETE' && !p.contains('/vet/')) {
      final s = {...?_activeSession, 'status': 'CANCELLED'};
      _activeSession = null;
      return ok(s);
    }
    if (RegExp(r'/consult-sessions/(\d+)/rating$').hasMatch(p)) {
      _activeSession = null;
      return ok({'id': 1, 'status': 'CLOSED', 'closedReason': 'RATED', 'source': 'DIRECT', 'waitingElapsedSeconds': 0, 'timedOut': false, 'alreadyActive': false});
    }
    if (RegExp(r'/consult-sessions/(\d+)/rating-prompted$').hasMatch(p)) return ok();
    if (p.endsWith('/consult/history') && m == 'GET') return ok(_envelope(_consultHistory));

    // ---------- 兽医工作台 ----------
    // 镜像 OnlineStatusResponse{online, status}：status 是 VetPresenceStatus 名（ONLINE/OFFLINE），App 暂只读 online 但 mock 须齐全。
    if (p.endsWith('/vet/online-status') && m == 'GET') return ok({'online': true, 'status': 'ONLINE'});
    if (p.endsWith('/vet/online-status') && m == 'PUT') {
      final online = (body['online'] ?? true) as bool;
      return ok({'online': online, 'status': online ? 'ONLINE' : 'OFFLINE'});
    }
    if (p.endsWith('/vet/heartbeat') && m == 'POST') return ok();
    if (p.endsWith('/vet/consult-sessions/waiting') && m == 'GET') {
      return ok([
        {'sessionId': 8001, 'source': 'DIRECT', 'aiDangerLevel': 'YELLOW', 'symptomPreview': '猫咪持续打喷嚏两天', 'imageCount': 2, 'waitingElapsedSeconds': 45},
        {'sessionId': 8002, 'source': 'AI_UPGRADE', 'aiDangerLevel': 'RED', 'symptomPreview': '误食异物', 'imageCount': 1, 'waitingElapsedSeconds': 120},
      ]);
    }
    final vetAccept = RegExp(r'/vet/consult-sessions/(\d+)/accept$').firstMatch(p);
    if (vetAccept != null && m == 'POST') {
      return ok({'id': int.parse(vetAccept.group(1)!), 'status': 'IN_PROGRESS', 'source': 'DIRECT', 'userId': 1, 'imConversationId': 'mock-im', 'hasAiContext': true});
    }
    final vetGet = RegExp(r'/vet/consult-sessions/(\d+)$').firstMatch(p);
    if (vetGet != null && m == 'GET') {
      return ok({'id': int.parse(vetGet.group(1)!), 'status': 'IN_PROGRESS', 'source': 'DIRECT', 'userId': 1, 'imConversationId': 'mock-im', 'hasAiContext': true});
    }
    if (RegExp(r'/vet/consult-sessions/(\d+)/ai-context$').hasMatch(p)) {
      return ok({'hasAiContext': true, 'dangerLevel': 'YELLOW', 'symptomText': '猫咪持续打喷嚏两天,精神尚可', 'imageUrls': []});
    }
    if (RegExp(r'/vet/consult-sessions/(\d+)/assist$').hasMatch(p)) {
      return ok({'aiReferenceReply': '建议观察体温与食欲,保持环境清洁,若 24 小时无改善请复诊。', 'historySummaries': []});
    }
    if (RegExp(r'/vet/consult-sessions/(\d+)/end$').hasMatch(p)) {
      return ok({'id': 1, 'status': 'PENDING_CLOSE', 'source': 'DIRECT', 'userId': 1, 'imConversationId': 'mock-im', 'hasAiContext': true});
    }
    if (RegExp(r'/vet/consult-sessions/(\d+)/notify-reply$').hasMatch(p)) return ok();

    // ---------- 通知 / 版本 / 媒体 ----------
    if (p.endsWith('/notifications') && m == 'GET') return ok(_envelope(_notifications));
    if (p.endsWith('/notifications/unread-count') && m == 'GET') {
      return ok({'count': _notifications.where((e) => e['read'] == false).length});
    }
    if (p.endsWith('/notifications/read-all') && m == 'POST') {
      for (final n in _notifications) {
        n['read'] = true;
      }
      return ok();
    }
    final notifRead = RegExp(r'/notifications/([^/]+)/read$').firstMatch(p);
    if (notifRead != null && m == 'POST') {
      final tok = notifRead.group(1);
      for (final n in _notifications) {
        if (n['_token'] == tok) n['read'] = true;
      }
      return ok();
    }
    if (p.endsWith('/app-version') && m == 'GET') {
      return ok({'latestVersion': '1.0.0', 'minSupportedVersion': '1.0.0', 'iosStoreUrl': null, 'androidStoreUrl': null});
    }
    if (p.endsWith('/media/sts-credentials') && m == 'POST') {
      return ok({'accessKeyId': 'mock', 'accessKeySecret': 'mock', 'securityToken': 'mock',
        'expiration': _iso(const Duration(hours: -1)), 'bucket': 'mock-bucket', 'region': 'mock',
        'endpoint': 'https://mock.example', 'uploadDir': 'mock/', 'cdnBaseUrl': 'https://mock.example'});
    }

    // 未覆盖端点：返回合理空成功(避免触网/崩溃),并 warn。
    if (kDebugMode) debugPrint('[MOCK] 未覆盖端点 → 空成功: $m $p');
    if (m == 'GET') return ok(_envelope([]));
    return ok({});
  }

  /// 条目的事件日期键（yyyy-MM-dd）：快乐时刻取 eventDate，健康事件取 date 的日期段。
  String _itemDateKey(Map<String, dynamic> e) {
    final ev = e['eventDate'] as String?;
    if (ev != null) return ev;
    return (e['date'] as String).substring(0, 10);
  }

  /// 按年月聚合 _timeline → 日历有记录日格子（镜像后端 /me/calendar）。
  Map<String, dynamic> _calendarFor(int year, int month) {
    final prefix = '$year-${month.toString().padLeft(2, '0')}-';
    final byDay = <int, Map<String, dynamic>>{};
    for (final e in _timeline) {
      final key = _itemDateKey(e);
      if (!key.startsWith(prefix)) continue;
      final day = int.parse(key.substring(8, 10));
      final happy = e['kind'] == 'HAPPY_MOMENT';
      final health = e['kind'] == 'HEALTH_EVENT';
      final cell = byDay[day] ??=
          {'day': day, 'firstImageUrl': null, 'hasHappyMoment': false, 'hasHealthEvent': false};
      if (happy) {
        cell['hasHappyMoment'] = true;
        final imgs = (e['imageUrls'] as List?) ?? const [];
        if (cell['firstImageUrl'] == null && imgs.isNotEmpty) cell['firstImageUrl'] = imgs.first;
      }
      if (health) cell['hasHealthEvent'] = true;
    }
    final days = byDay.values.toList()..sort((a, b) => (a['day'] as int).compareTo(b['day'] as int));
    return {'year': year, 'month': month, 'days': days};
  }

  DioException _notFound(RequestOptions o) => DioException(
        requestOptions: o,
        response: Response(requestOptions: o, statusCode: 404),
        type: DioExceptionType.badResponse,
      );

  /// 构造 RFC 9457 ProblemDetail 错误（mock 镜像后端语义，便于离线演示拦截/校验态）。
  DioException _problem(RequestOptions o, int status, String typeSlug, String detail) =>
      DioException(
        requestOptions: o,
        response: Response(requestOptions: o, statusCode: status, data: {
          'type': 'https://petgo/errors/$typeSlug',
          'status': status,
          'detail': detail,
        }),
        type: DioExceptionType.badResponse,
      );
}
