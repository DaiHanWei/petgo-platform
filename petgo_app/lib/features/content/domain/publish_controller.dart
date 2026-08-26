import 'package:flutter/foundation.dart';

import '../data/content_repository.dart';
import 'content_type.dart';
import 'feed_image_layout.dart';
import 'image_crop.dart';

/// 单张图片上传状态（AC3 状态机）。
enum ImageUploadStatus { pending, uploading, success, failed }

/// 单张待发图片项（内存草稿，无持久化）。
class ImageUploadItem {
  ImageUploadItem(this.bytes, {this.status = ImageUploadStatus.pending, this.url, this.size});

  final Uint8List bytes;
  ImageUploadStatus status;
  String? url;

  /// 这张图的原始宽高（V1.1.6 Story 3.5）。
  ///
  /// 🔴 客户端**此前从来没上报过尺寸** —— 后端 Story 3.1 的接口早就收，
  /// 但发布请求里没这个字段，于是每条新帖都要等服务端异步下载图片再测一遍。
  /// 那意味着 Story 3.3 说的「新内容零跳动」要等兜底任务跑完才成立，
  /// 刚发完就刷首页的人看到的仍是占位比例。
  ///
  /// 发布路径本来就在解码图片，宽高顺手就有。量不出来时留 null，由服务端兜底。
  final ImageSize? size;
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

  /// 「同步到 Moment」开关（Story 4.2 · FR-83）。**默认开启**：发 Diary 的默认行为仍是公开分享。
  ///
  /// 仅对 Diary（`growthMoment`）有意义 —— Moment / Tips 本就公开进 Feed，不渲染该开关。
  /// 关掉 → 发布请求带 `visibility=PRIVATE`，该条只进作者自己的成长档案。
  /// ⚠️ 发布后不可更改（FR-83 AC7），所以这里只影响创建，不存在事后切换入口。
  bool syncToMoment = true;
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

  /// 是否有图片正在上传（即选即传期间）。上传中禁止发布——发布按钮置灰。
  bool get isUploading =>
      items.any((i) => i.status == ImageUploadStatus.uploading);

  bool get canPublish =>
      !publishing &&
      !isUploading &&
      textWithinLimit &&
      (text.trim().isNotEmpty || items.isNotEmpty);

  void setType(ContentType t) {
    type = t;
    // 切到 Moment / Tips 时把开关复位为默认开启：它们不渲染开关，若残留 false
    // 会让「看不见的开关」偷偷把公开内容发成私密。
    if (t != ContentType.growthMoment) {
      syncToMoment = true;
    }
    notifyListeners();
  }

  void setSyncToMoment(bool value) {
    syncToMoment = value;
    notifyListeners();
  }

  /// 主按钮文案是否为「分享」（开关开）；关 → 「保存」（Story 4.2 · AC2，PRD 未提、UI 稿 P2 独有）。
  bool get isSharing => type != ContentType.growthMoment || syncToMoment;

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
    // 只读文件头，不整张解码 —— 9 张图各解一遍会让加图这一步明显卡顿。
    items.add(ImageUploadItem(bytes, size: imageSizeOf(bytes)));
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
      // 🛡 **与图片同序等长**：后端对长度不符的处理是**整组作废**（不做部分采信），
      // 所以量不出来的位置也要占一个 null，绝不能"跳过不放"。
      final sizes = items.map((i) => i.size).toList();
      final growth = type == ContentType.growthMoment;
      return await repository.publish(
        type: type,
        petId: growth ? petId : null,
        text: text.trim().isEmpty ? null : text.trim(),
        imageUrls: urls,
        imageSizes: sizes,
        eventDate: growth ? (eventDate ?? DateTime.now()) : null,
        idempotencyKey: idempotencyKey,
        // 非 Diary 恒公开；Diary 由开关决定。
        syncToMoment: growth ? syncToMoment : true,
      );
    } finally {
      publishing = false;
      notifyListeners();
    }
  }
}
