#!/bin/bash
# 虚拟主机版（wolfweb）自检链
cd "G:/狼人杀/tools/uireflow"
LOG=wolfweb-suite.log
: > $LOG
P=./php-portable/php.exe

echo "########## 1/4 audit_engine.php（引擎不变量 3板×250局） ##########" | tee -a $LOG
"$P" audit_engine.php 250 2>&1 | tee -a $LOG
echo "EXIT_AUDIT_ENGINE=$?" | tee -a $LOG

echo "########## 2/4 wtest.mjs（全链路 E2E：安装向导→整局→社区→管理） ##########" | tee -a $LOG
node wtest.mjs 2>&1 | tee -a $LOG
echo "EXIT_WTEST=$?" | tee -a $LOG

echo "########## 3/4 secprobe.mjs（安全/越权 36 项） ##########" | tee -a $LOG
node secprobe.mjs 2>&1 | tee -a $LOG
echo "EXIT_SECPROBE=$?" | tee -a $LOG

echo "########## 4/4 conprobe.mjs（并发一致性+双人组队 14 项） ##########" | tee -a $LOG
node conprobe.mjs 2>&1 | tee -a $LOG
echo "EXIT_CONPROBE=$?" | tee -a $LOG

echo "########## WOLFWEB SUITE DONE ##########" | tee -a $LOG
