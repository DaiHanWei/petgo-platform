import 'package:flutter/material.dart';

import '../../features/content/domain/feed_image_layout.dart';
import 'app_image.dart';
import 'post_cover.dart';

/// Feed 卡片的图片区（V1.1.6 Story 3.3 · FR-71 / AD-6）。
///
/// 职责就两件：**按三段口径定高**、**加载期给出占位**。
///
/// ## 为什么要有占位（这是必做项，不是优化）
/// 改版前图片区是写死的 4:3，图片还没下载完高度就已经定了 —— 不存在布局跳动。
/// 改成按实际比例渲染之后，**跳动是本次改版新引入的问题**。
///
/// 分两种情况：
/// - **新内容**（发布时记了宽高）：加载前就知道高度，**零跳动**。
/// - **存量内容**（永远没有宽高）：先按 1:1 预留，图片解码出来后再调整到真实比例。
///   因为有 clamp，这次调整的幅度**有硬上下界**（最多变高三成或变矮四分之一）。
///
/// ## ⚠️ 为什么不用统一的图片组件
/// 统一组件返回的就是一个图片对象，而它的既有测试直接把返回值当图片对象用；
/// 要拿到"解码后的真实尺寸"必须监听图片流，这里改用「取 provider + 自己渲染」的写法
/// （项目里宠物资料卡已有同款先例），**不动统一组件的签名**，那批测试因此不受影响。
class FeedImage extends StatefulWidget {
  const FeedImage({
    super.key,
    required this.url,
    required this.type,
    required this.declaredSize,
    required this.width,
    required this.maxImageHeight,
    this.thumbWidth = 800,
  });

  final String url;

  /// 内容类型：占位彩块按它取色与 emoji。
  final String type;

  /// 后端下发的原始尺寸；存量内容为 null。
  final ImageSize? declaredSize;

  /// 图片区显示宽度（通栏 = 屏宽）。
  final double width;

  /// 高度护栏上限。
  final double maxImageHeight;

  /// OSS 缩略图宽度（物理像素）。Feed 全宽封面取缩略图省流量、滚动更顺。
  final int thumbWidth;

  @override
  State<FeedImage> createState() => _FeedImageState();
}

class _FeedImageState extends State<FeedImage> {
  ImageProvider? _provider;
  ImageStream? _stream;
  ImageStreamListener? _listener;

  /// 解码后量到的真实尺寸；仅在后端**没给**尺寸时才去拿。
  ImageSize? _decoded;

  @override
  void didChangeDependencies() {
    super.didChangeDependencies();
    _provider = AppImage.provider(widget.url, thumbWidth: widget.thumbWidth);
    _subscribeIfNeeded();
  }

  @override
  void didUpdateWidget(covariant FeedImage old) {
    super.didUpdateWidget(old);
    if (old.url != widget.url || old.thumbWidth != widget.thumbWidth) {
      _decoded = null;
      _unsubscribe();
      _provider = AppImage.provider(widget.url, thumbWidth: widget.thumbWidth);
      _subscribeIfNeeded();
    }
  }

  /// 🔴 后端给了尺寸就**不监听** —— 高度已经确定，去解码里再量一遍毫无意义。
  ///
  /// 监听用的是与渲染**同一个** provider，因此共用图片缓存，不会多下载一次。
  void _subscribeIfNeeded() {
    if (widget.declaredSize != null || _provider == null) return;
    final stream = _provider!.resolve(createLocalImageConfiguration(context));
    if (stream.key == _stream?.key) return;
    _unsubscribe();
    _listener = ImageStreamListener((info, _) {
      final size = ImageSize(info.image.width, info.image.height);
      if (!mounted || !size.isUsable) return;
      if (_decoded?.w == size.w && _decoded?.h == size.h) return;
      setState(() => _decoded = size);
    }, onError: (_, _) {
      // 失败态由 errorBuilder 出占位，这里不需要额外处理；吞掉以免冒泡成未捕获异常。
    });
    _stream = stream..addListener(_listener!);
  }

  void _unsubscribe() {
    if (_stream != null && _listener != null) _stream!.removeListener(_listener!);
    _stream = null;
    _listener = null;
  }

  @override
  void dispose() {
    _unsubscribe();
    super.dispose();
  }

  @override
  Widget build(BuildContext context) {
    final placeholder = PostCoverPlaceholder(type: widget.type);
    final aspect = resolveFeedImageAspect(
      size: widget.declaredSize ?? _decoded,
      width: widget.width,
      maxImageHeight: widget.maxImageHeight,
    );
    final provider = _provider;

    return AspectRatio(
      aspectRatio: aspect,
      child: provider == null
          ? placeholder
          : Image(
              image: provider,
              fit: BoxFit.cover,
              width: double.infinity,
              height: double.infinity,
              // 换图时先留住上一帧，避免中间闪一下白（同款先例见宠物资料卡）。
              gaplessPlayback: true,
              frameBuilder: (context, child, frame, wasSynchronouslyLoaded) =>
                  (wasSynchronouslyLoaded || frame != null) ? child : placeholder,
              errorBuilder: (context, error, stack) => placeholder,
            ),
    );
  }
}
