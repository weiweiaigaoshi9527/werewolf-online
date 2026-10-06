#!/bin/bash
# 主项目 + 线上演示 自检链
cd "G:/狼人杀/tools/uireflow"
LOG=main-suite.log
: > $LOG
run() {
  echo "########## $1 ##########" | tee -a $LOG
  shift
  "$@" 2>&1 | tail -25 | tee -a $LOG
  echo "EXIT=$? @ $(date +%H:%M:%S)" | tee -a $LOG
  echo | tee -a $LOG
}

run "demoprobe.mjs（线上演示页 16 项）" node demoprobe.mjs
run "audit_json.mjs（changelog/manifest 静态审计）" node audit_json.mjs
run "audit_manifest.mjs（下载清单一致性）" node audit_manifest.mjs
run "verify11.mjs（11 轮修复回归 9 项）" node verify11.mjs
run "probe.mjs（13 项功能探针）" node probe.mjs
run "probe_duo.mjs（双人组队链路）" node probe_duo.mjs
run "probe400.mjs（400 错误路径）" node probe400.mjs
run "leaktest.mjs（信息泄露 120 轮）" node leaktest.mjs
run "narrtest.mjs（旁白时间轴一致性）" node narrtest.mjs
run "voicetest.mjs（语音协议 ASR/TTS）" node voicetest.mjs
run "hmax.mjs（页面最大高度）" node hmax.mjs
run "adminscroll.mjs（管理页滚动）" node adminscroll.mjs
run "coldload.mjs（冷加载）" node coldload.mjs
run "verify2b.mjs（2 轮回归）" node verify2b.mjs
run "verify6b.mjs（观战/开关/头像/红点/VIP）" node verify6b.mjs
run "verify7.mjs（第 7 轮回归）" node verify7.mjs
run "shot.mjs（31 屏截图 + JS 报错）" node shot.mjs
run "shot2.mjs（主题+体验增强）" node shot2.mjs
run "shot3.mjs（补充屏）" node shot3.mjs
run "webtest.mjs（网页端截图）" node webtest.mjs
run "edgecheck.mjs（Edge 兼容检查）" node edgecheck.mjs
echo "########## MAIN SUITE DONE ##########" | tee -a $LOG
