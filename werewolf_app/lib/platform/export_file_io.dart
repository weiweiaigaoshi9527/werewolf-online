import 'dart:io' show File;
import 'dart:typed_data';

import 'package:file_picker/file_picker.dart';

/// 桌面 / 移动：file_picker 8.1.2 的 saveFile 支持 bytes 参数；个别平台只回路径不落盘，
/// 因此若路径不存在或文件为空，再用 dart:io 的 File 兜底写一次。
/// 返回落盘路径；用户取消返回 null。
///
/// 调用方只传 `fileName` / `allowedExtensions` / `bytes` / 对话框标题，落盘细节全在这里。
Future<String?> saveExportFile({
  required String dialogTitle,
  required String fileName,
  required List<String> allowedExtensions,
  required Uint8List bytes,
}) async {
  final path = await FilePicker.platform.saveFile(
    dialogTitle: dialogTitle,
    fileName: fileName,
    type: FileType.custom,
    allowedExtensions: allowedExtensions,
    bytes: bytes,
  );
  if (path == null) return null; // 用户取消
  try {
    final f = File(path);
    if (!await f.exists() || (await f.length()) == 0) {
      await f.writeAsBytes(bytes, flush: true);
    }
  } catch (_) {}
  return path;
}
