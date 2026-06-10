import 'package:flutter/foundation.dart';

import '../data/content_repository.dart';
import 'content_type.dart';

/// 单张图片上传状态（AC3 状态机）。
enum ImageUploadStatus { pending, uploading, success, failed }

/// 单张待发图片项（内存草稿，无持久化）。
class ImageUploadItem {
  ImageUploadItem(this.bytes, {this.status = ImageUploadStatus.pending, this.url});

  final Uint8List bytes;
  ImageUploadStatus status;
  String? url;
}

/// 文字上限（FR：1000 字符实时计数，超出禁止发布）。
const int kMaxPostTextLength = 1000;

/// 单张超 10MB 压不下时由处理层抛错；本控制器只管上传编排。
const int kMaxImages = 9;

/// Publish Compose 上传状态机（Story 2.3 · F4）。
///
/// 持每图独立状态；「重试」**仅重传失败件**，文字在内存保留；控制器随 sheet dispose 即清空
/// （无持久草稿，NFR-10）。各依赖注入，便于 L0 单测。
class PublishController extends ChangeNotifier {
  PublishController({
    required this.uploadOne,
    required this.repository,
  });

  /// 单张上传函数（成功返回对外 URL，失败抛异常）。注入便于 L0 单测。
  final Future<String> Function(Uint8List bytes) uploadOne;
  final ContentRepository repository;

  ContentType type = ContentType.daily;
  String text = '';
  final List<ImageUploadItem> items = <ImageUploadItem>[];
  bool publishing = false;

  /// 成长日历事件日期（F9）：仅 GROWTH_MOMENT 有意义，决定档案侧显示位置（与发布时间解耦）。
  /// 不可未来——由 UI date picker 与服务端共同守护。
  DateTime? eventDate;

  int get remainingChars => kMaxPostTextLength - text.length;
  bool get textWithinLimit => text.length <= kMaxPostTextLength;
  bool get hasFailed => items.any((i) => i.status == ImageUploadStatus.failed);
  bool get allUploaded =>
      items.every((i) => i.status == ImageUploadStatus.success);

  bool get canPublish =>
      !publishing && textWithinLimit && (text.trim().isNotEmpty || items.isNotEmpty);

  void setType(ContentType t) {
    type = t;
    notifyListeners();
  }

  void setText(String value) {
    text = value;
    notifyListeners();
  }

  /// 设成长日历事件日期（仅日期，去时分；未来日期由调用方/picker 拦截）。
  void setEventDate(DateTime date) {
    eventDate = DateTime(date.year, date.month, date.day);
    notifyListeners();
  }

  /// 加入一张待传图片（≤9）。返回是否成功加入。
  bool addImage(Uint8List bytes) {
    if (items.length >= kMaxImages) return false;
    items.add(ImageUploadItem(bytes));
    notifyListeners();
    return true;
  }

  void removeImage(int index) {
    if (index >= 0 && index < items.length) {
      items.removeAt(index);
      notifyListeners();
    }
  }

  /// 上传所有待传/失败项。
  Future<void> uploadAll() => _upload((i) =>
      i.status == ImageUploadStatus.pending || i.status == ImageUploadStatus.failed);

  /// 仅重传失败件（AC3 核心）。文字与已成功项不受影响。
  Future<void> retryFailed() => _upload((i) => i.status == ImageUploadStatus.failed);

  Future<void> _upload(bool Function(ImageUploadItem) selector) async {
    final targets = items.where(selector).toList();
    for (final item in targets) {
      item.status = ImageUploadStatus.uploading;
      notifyListeners();
      try {
        item.url = await uploadOne(item.bytes);
        item.status = ImageUploadStatus.success;
      } catch (_) {
        item.status = ImageUploadStatus.failed;
      }
      notifyListeners();
    }
  }

  /// 发布：先确保图片全部上传成功；有失败件返回 null（调用方提示重试）。
  /// 成功则提交 post，返回新 post id。[idempotencyKey] 客户端生成防重。
  Future<int?> publish({required String idempotencyKey, int? petId}) async {
    if (!canPublish) return null;
    publishing = true;
    notifyListeners();
    try {
      await uploadAll();
      if (!allUploaded) return null; // 仍有失败件 → 让用户重试，不提交
      final urls = items.map((i) => i.url!).toList();
      final growth = type == ContentType.growthMoment;
      return await repository.publish(
        type: type,
        petId: growth ? petId : null,
        text: text.trim().isEmpty ? null : text.trim(),
        imageUrls: urls,
        eventDate: growth ? (eventDate ?? DateTime.now()) : null,
        idempotencyKey: idempotencyKey,
      );
    } finally {
      publishing = false;
      notifyListeners();
    }
  }
}
