; ============================================================
;  狼人杀 Online · 服务端（Windows x64）安装包脚本
;  编译：makensis install.nsi   （需在 pkg 目录下执行）
;  产物：werewolf-server-setup-1.0.0-win64.exe
; ============================================================
Unicode true
!include "MUI2.nsh"

!define APP_NAME    "狼人杀 Online 服务端"
!define APP_VER     "1.0.0"
!define APP_PUBLISH "Werewolf Online"
!define UNINST_KEY  "Software\Microsoft\Windows\CurrentVersion\Uninstall\WerewolfServer"

Name "${APP_NAME} ${APP_VER}"
OutFile "werewolf-server-setup-1.0.0-win64.exe"
InstallDir "$LOCALAPPDATA\WerewolfServer"
InstallDirRegKey HKCU "Software\WerewolfServer" "InstallDir"
RequestExecutionLevel user
SetCompressor /SOLID lzma

!define MUI_ABORTWARNING
!insertmacro MUI_PAGE_WELCOME
!insertmacro MUI_PAGE_COMPONENTS
!insertmacro MUI_PAGE_DIRECTORY
!insertmacro MUI_PAGE_INSTFILES

!define MUI_FINISHPAGE_RUN
!define MUI_FINISHPAGE_RUN_TEXT "安装完成后立即启动服务端（推荐）"
!define MUI_FINISHPAGE_RUN_FUNCTION LaunchServer
!define MUI_FINISHPAGE_SHOWREADME "$INSTDIR\使用说明.txt"
!define MUI_FINISHPAGE_SHOWREADME_TEXT "查看使用说明"
!insertmacro MUI_PAGE_FINISH

!insertmacro MUI_UNPAGE_CONFIRM
!insertmacro MUI_UNPAGE_INSTFILES
!insertmacro MUI_LANGUAGE "SimpChinese"

Function LaunchServer
    ExecShell "open" "$INSTDIR\start.bat"
FunctionEnd

; ------------------------------------------------------------ 主程序
Section "服务端程序与内置 Java 21 运行时（必需）" SecCore
    SectionIn RO
    SetOutPath "$INSTDIR"
    File "werewolf-server\start.bat"
    File "werewolf-server\stop.bat"
    File "werewolf-server\stop.ps1"
    File "werewolf-server\make-cert.bat"
    File "werewolf-server\lan-ip.ps1"
    File "werewolf-server\voice-up.ps1"
    File "werewolf-server\werewolf-online.jar"
    File "werewolf-server\使用说明.txt"
    File /r "werewolf-server\runtime"

    ; 用户数据目录（重装/升级时不会被清空）
    CreateDirectory "$INSTDIR\config"
    CreateDirectory "$INSTDIR\data"
    CreateDirectory "$INSTDIR\uploads"
    CreateDirectory "$INSTDIR\logs"

    WriteRegStr HKCU "Software\WerewolfServer" "InstallDir" "$INSTDIR"
    WriteRegStr HKCU "${UNINST_KEY}" "DisplayName"     "${APP_NAME}"
    WriteRegStr HKCU "${UNINST_KEY}" "DisplayVersion"  "${APP_VER}"
    WriteRegStr HKCU "${UNINST_KEY}" "Publisher"       "${APP_PUBLISH}"
    WriteRegStr HKCU "${UNINST_KEY}" "InstallLocation" "$INSTDIR"
    WriteRegStr HKCU "${UNINST_KEY}" "UninstallString" '"$INSTDIR\uninstall.exe"'
    WriteRegStr HKCU "${UNINST_KEY}" "DisplayIcon"     "$INSTDIR\start.bat"
    WriteRegDWORD HKCU "${UNINST_KEY}" "NoModify" 1
    WriteRegDWORD HKCU "${UNINST_KEY}" "NoRepair" 1
    WriteUninstaller "$INSTDIR\uninstall.exe"
SectionEnd

; ------------------------------------------------------------ 快捷方式
Section "开始菜单快捷方式" SecSM
    CreateDirectory "$SMPROGRAMS\狼人杀服务端"
    CreateShortcut "$SMPROGRAMS\狼人杀服务端\启动服务端.lnk" "$INSTDIR\start.bat" "" "$INSTDIR\start.bat" 0
    CreateShortcut "$SMPROGRAMS\狼人杀服务端\停止服务端.lnk" "$INSTDIR\stop.bat" "/y" "$INSTDIR\stop.bat" 0
    CreateShortcut "$SMPROGRAMS\狼人杀服务端\使用说明.lnk"   "$INSTDIR\使用说明.txt"
    CreateShortcut "$SMPROGRAMS\狼人杀服务端\卸载.lnk"       "$INSTDIR\uninstall.exe"
SectionEnd

Section "桌面快捷方式" SecDesk
    CreateShortcut "$DESKTOP\狼人杀服务端.lnk" "$INSTDIR\start.bat" "" "$INSTDIR\start.bat" 0
SectionEnd

!insertmacro MUI_FUNCTION_DESCRIPTION_BEGIN
    !insertmacro MUI_DESCRIPTION_TEXT ${SecCore} "服务端程序、启动脚本与内置 Java 21 运行时（约 115 MB）。"
    !insertmacro MUI_DESCRIPTION_TEXT ${SecSM}   "在开始菜单创建 启动 / 停止 / 使用说明 / 卸载 快捷方式。"
    !insertmacro MUI_DESCRIPTION_TEXT ${SecDesk} "在桌面创建「狼人杀服务端」启动快捷方式。"
!insertmacro MUI_FUNCTION_DESCRIPTION_END

; ------------------------------------------------------------ 卸载
Section "Uninstall"
    ; 先停掉本目录的服务端（避免 jar / 运行时被占用而删不掉）
    ExecWait '"$INSTDIR\stop.bat" /y'

    Delete "$SMPROGRAMS\狼人杀服务端\启动服务端.lnk"
    Delete "$SMPROGRAMS\狼人杀服务端\停止服务端.lnk"
    Delete "$SMPROGRAMS\狼人杀服务端\使用说明.lnk"
    Delete "$SMPROGRAMS\狼人杀服务端\卸载.lnk"
    RMDir  "$SMPROGRAMS\狼人杀服务端"
    Delete "$DESKTOP\狼人杀服务端.lnk"

    Delete "$INSTDIR\start.bat"
    Delete "$INSTDIR\stop.bat"
    Delete "$INSTDIR\stop.ps1"
    Delete "$INSTDIR\make-cert.bat"
    Delete "$INSTDIR\lan-ip.ps1"
    Delete "$INSTDIR\voice-up.ps1"
    Delete "$INSTDIR\使用说明.txt"
    Delete "$INSTDIR\werewolf-online.jar"
    RMDir /r "$INSTDIR\runtime"
    RMDir /r "$INSTDIR\logs"
    ; data / uploads / config 里的账号、战绩、头像、证书一律保留（只删空目录）
    RMDir "$INSTDIR\config"
    RMDir "$INSTDIR\data"
    RMDir "$INSTDIR\uploads"
    Delete "$INSTDIR\uninstall.exe"
    RMDir "$INSTDIR"

    DeleteRegKey HKCU "${UNINST_KEY}"
    DeleteRegKey HKCU "Software\WerewolfServer"

    MessageBox MB_ICONINFORMATION|MB_OK "已卸载。$\r$\n$\r$\n你的游戏数据（账号、战绩、头像）与证书仍保留在：$\r$\n$INSTDIR$\r$\n如需彻底清空，请手动删除该目录。"
SectionEnd
