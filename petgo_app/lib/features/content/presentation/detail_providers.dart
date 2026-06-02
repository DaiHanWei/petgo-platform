import 'package:flutter_riverpod/flutter_riverpod.dart';

import '../data/detail_repository.dart';
import '../domain/content_detail.dart';

/// 内容详情（按 id 的 family）。AsyncValue 三态：loading 骨架 / data / error（多态分类）。
final detailProvider = FutureProvider.family<ContentDetail, int>(
  (ref, id) => ref.read(detailRepositoryProvider).getDetail(id),
);
