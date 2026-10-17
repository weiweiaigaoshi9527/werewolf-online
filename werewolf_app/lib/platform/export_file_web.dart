// Web 端只能用 SDK 自带的 dart:html 触发下载：package:web 要新增依赖（本仓不允许），
// 所以这里显式豁免该 lint（本文件只在 Web 编译时被条件导入，不影响桌面/移动/测试）。
// ignore: avoid_web_libraries_in_flutter
import 'dart:html' as html;
import 'dart:typed_data';

/// Web：浏览器里没有可写的本地路径，退化成「触发一次下载」
/// （Blob + 隐藏的 `<a download>`，与网页端后台导出 CSV 的做法一致）。
///
/// 浏览器不会把保存位置告诉页面，所以这里返回文件名供界面提示；
/// 用户若在浏览器的保存框里点了取消，页面也拿不到通知，一律按「已触发下载」处理。
Future<String?> saveExportFile({
  required String dialogTitle,
  required String fileName,
  required List<String> allowedExtensions,
  required Uint8List bytes,
}) async {
  final mime = fileName.toLowerCase().endsWith('.csv')
      ? 'text/csv;charset=utf-8'
      : 'application/octet-stream';
  final blob = html.Blob(<Object>[bytes], mime);
  final url = html.Url.createObjectUrlFromBlob(blob);
  final a = html.AnchorElement(href: url)
    ..download = fileName
    ..style.display = 'none';
  html.document.body?.children.add(a);
  a.click();
  a.remove();
  html.Url.revokeObjectUrl(url);
  return fileName;
}
