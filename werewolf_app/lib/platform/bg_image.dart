/// 自定义背景图的平台适配门面。
///
/// 桌面 / 移动读本地文件（file_picker 回的是真实路径）；Web 拿不到本地路径，
/// 只有 file_picker 回传的图片字节，用 `MemoryImage` 渲染。
///
/// 两个实现同名同签名：[customBgImage] 返回 null 即表示「当前平台没有可用的自定义背景」，
/// 调用方据此退回主题渐变底色。
library;

export 'bg_image_io.dart' if (dart.library.js_interop) 'bg_image_web.dart';
