/// 「后台导出文件」的平台适配门面。
///
/// 桌面 / 移动落盘（file_picker 的 saveFile + IO 侧兜底写一次）；
/// Web 没有本地文件系统，退化成触发浏览器下载。
/// 两者返回「落盘路径 / 下载文件名」，返回 null 表示用户取消。
library;

export 'export_file_io.dart' if (dart.library.js_interop) 'export_file_web.dart';
