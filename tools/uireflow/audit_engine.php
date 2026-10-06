<?php
/**
 * 引擎不变量审计：三块板子各连打 N 局，逐层校验状态完整性与规则正确性。
 * 用法：php audit_engine.php [每板局数]
 */
require 'C:/Users/weiwei/Documents/wolfweb/app.php';
require 'C:/Users/weiwei/Documents/wolfweb/schema.php';
require 'C:/Users/weiwei/Documents/wolfweb/engine.php';
require 'C:/Users/weiwei/Documents/wolfweb/policy.php';
require 'C:/Users/weiwei/Documents/wolfweb/tick.php';

$N = (int)($argv[1] ?? 120);
$boards = [
    ['name' => '标准 9 人', 'players' => 9, 'roles' => ['werewolf' => 3, 'seer' => 1, 'witch' => 1, 'hunter' => 1, 'villager' => 3], 'sheriff' => false],
    ['name' => '进阶 10 人', 'players' => 10, 'roles' => ['werewolf' => 3, 'whiteWolf' => 1, 'seer' => 1, 'witch' => 1, 'guard' => 1, 'hunter' => 1, 'villager' => 2], 'sheriff' => false],
    ['name' => '暗夜 12 人', 'players' => 12, 'roles' => ['werewolf' => 4, 'seer' => 1, 'witch' => 1, 'guard' => 1, 'hunter' => 1, 'crow' => 1, 'silencer' => 1, 'villager' => 2], 'sheriff' => true],
];

$issues = [];
$stat = ['games' => 0, 'good' => 0, 'wolf' => 0, 'maxday' => 0, 'blowups' => 0, 'shoots' => 0, 'pks' => 0, 'sheriffs' => 0, 'saves' => 0, 'poisons' => 0, 'silenced' => 0, 'crowMarks' => 0, 'guardEmpty' => 0];

function fail(&$issues, $tag, $msg) { $issues[] = $tag . ': ' . $msg; }

foreach ($boards as $bi => $board) {
    for ($n = 0; $n < $N; $n++) {
        $tag = "板{$bi}局{$n}";
        $seats = [];
        for ($i = 0; $i < $board['players']; $i++) $seats[] = ['name' => 'P' . ($i + 1), 'userId' => 0, 'ai' => true];
        try {
            $g = Game::create($board, $seats);
        } catch (Throwable $e) {
            fail($issues, $tag, 'create 抛异常 ' . $e->getMessage());
            continue;
        }
        $stat['games']++;
        $aliveHist = [];       // seat => [1,1,0,...] 单调性
        $votesByDay = [];
        $exiledByDay = [];
        $deathsByNight = [];
        $speechBySeat = [];
        $lastGuard = -1;
        $steps = 0;
        $seenTypes = [];

        while (!$g->isGameOver() && $steps < 4000) {
            $steps++;
            $kind = $g->currentActionKind();
            $seat = $g->currentActor();
            if ($kind === '' || $seat === 0) { fail($issues, $tag, "阶段 {$g->st['phase']} 无可执行动作（可能卡死）"); break; }
            $p = $g->bySeat($seat);
            if (!$p) { fail($issues, $tag, "currentActor=$seat 不存在"); break; }
            $deadOk = in_array($kind, ['LAST_WORDS', 'SHOOT'], true); // 遗言/开枪本就是死者回合
            if (!$p['alive'] && !$deadOk) { fail($issues, $tag, "已出局的 $seat 号仍在行动（$kind）"); break; }
            if ($p['alive'] && $deadOk && $kind === 'SHOOT') { /* 放逐猎人开枪时已置死，正常 */ }
            $seenTypes[$kind] = true;

            $recent = [];
            foreach ($g->events() as $e) if ($e['type'] === 'SPEECH' || $e['type'] === 'LAST_WORDS') $recent[] = $e['detail'];

            try {
                // 与 tick 一致：白狼王白天有 8% 概率自爆
                if ($g->canBlowUp($seat) && ww_rnd(1, 100) <= 8) {
                    $goods = array_values(array_filter($g->aliveSeats(), function ($s) use ($g) { return !$g->isWolfRole($g->bySeat($s)['role']); }));
                    if ($goods) { $before = count($g->events()); ww_apply_auto($g, $seat, ['kind' => 'BLOWUP', 'target' => ww_pick($goods)]); goto after_apply; }
                }
                $act = ww_auto_action($g, $seat, $recent);
                $before = count($g->events());
                ww_apply_auto($g, $seat, $act);
                after_apply:
                $all = $g->events();
                for ($ei = $before; $ei < count($all); $ei++) {
                    $e = $all[$ei];
                    // 出局类事件必须真的把座位置死（防值语义回归）
                    if (in_array($e['type'], ['EXILE', 'DIE', 'BLOWUP'], true)) {
                        $dp = $g->bySeat((int)$e['actor']);
                        if ($dp && $dp['alive']) fail($issues, $tag, "{$e['type']} 事件后 {$e['actor']} 号仍存活（放逐/死亡未生效）");
                    }
                    if ($e['type'] === 'SHOOT' && $e['target']) {
                        $tp = $g->bySeat((int)$e['target']);
                        if ($tp && $tp['alive']) fail($issues, $tag, "开枪后 {$e['target']} 号仍存活");
                    }
                }
            } catch (Throwable $e) {
                fail($issues, $tag, "apply $kind/$seat 异常: " . $e->getMessage());
                break;
            }

            // 状态完整性
            $seatsSeen = [];
            foreach ($g->st['players'] as $pp) {
                if (!is_array($pp)) { fail($issues, $tag, 'players 数组出现 null 槽位（值语义腐坏）'); continue 2; }
                $seatsSeen[$pp['seat']] = true;
                $aliveHist[$pp['seat']][] = $pp['alive'] ? 1 : 0;
            }
            if (count($seatsSeen) !== $board['players']) fail($issues, $tag, '座位数不等于板子人数: ' . count($seatsSeen));

            // 守卫不可连守
            if ($kind === 'GUARD') {
                $t = (int)$g->st['night']['guarded'];
                if ($t !== 0 && $t === $lastGuard) fail($issues, $tag, "守卫连守 $t 号");
                if ($t === 0) $stat['guardEmpty']++;
                $lastGuard = $t;
            }
        }

        if (!$g->isGameOver()) { fail($issues, $tag, "未在 4000 步内结束（phase={$g->st['phase']} day={$g->st['day']}）"); continue; }
        $winner = $g->st['winner'];
        if (!in_array($winner, ['good', 'wolf'], true)) fail($issues, $tag, 'winner 非法: ' . $winner);
        $winner === 'good' ? $stat['good']++ : $stat['wolf']++;
        $stat['maxday'] = max($stat['maxday'], (int)$g->st['day']);

        // 复活检查
        foreach ($aliveHist as $s => $hist) {
            for ($i = 1; $i < count($hist); $i++) if ($hist[$i - 1] === 0 && $hist[$i] === 1) { fail($issues, $tag, "$s 号死而复生"); break; }
        }

        // 结算全翻牌 + 存活人数与胜负一致性
        $view = $g->viewFor(0, $g->events());
        $wAlive = count($g->wolvesAlive());
        $gAlive = count($g->aliveSeats()) - $wAlive;
        if ($winner === 'good' && $wAlive !== 0) fail($issues, $tag, "好人胜但狼仍活 $wAlive 只");
        if ($winner === 'wolf' && !($wAlive >= $gAlive && $wAlive > 0)) fail($issues, $tag, "狼人胜但 $wAlive 狼 < $gAlive 好人");
        foreach ($view['seats'] as $vs) if (empty($vs['role'])) fail($issues, $tag, "结算未翻牌: {$vs['seat']}号");

        // 事件层校验
        $dayVotes = [];
        $lastWordsSeats = [];
        foreach ($g->events() as $e) {
            if ($e['type'] === 'SPEECH' && isset($speechBySeat[$e['actor']]) && $e['detail'] === end($speechBySeat[$e['actor']])) {
                // 同一人连续两次完全同句（托管台词池轮换失效）
                fail($issues, $tag, $e['actor'] . ' 号连续两次说同一句: ' . $e['detail']);
            }
            if ($e['type'] === 'SPEECH') { $speechBySeat[$e['actor']][] = $e['detail']; }
            if ($e['type'] === 'BLOWUP') $stat['blowups']++;
            if ($e['type'] === 'SHOOT') $stat['shoots']++;
            if ($e['type'] === 'SHERIFF') $stat['sheriffs']++;
            if ($e['type'] === 'SILENCED') $stat['silenced']++;
            if ($e['type'] === 'CROW') $stat['crowMarks']++;
            if ($e['type'] === 'WITCH') { if (strpos($e['detail'], '解药') !== false) $stat['saves']++; if (strpos($e['detail'], '毒药') !== false) $stat['poisons']++; }
            if ($e['type'] === 'NARR' && strpos($e['detail'], 'PK') !== false) $stat['pks']++;
            if ($e['type'] === 'VOTE_RESULT') {
                $pairs = explode('，', $e['detail']);
                foreach ($pairs as $pr) if (!preg_match('/^\S+ → \S+$/', trim($pr))) { fail($issues, $tag, '票型格式异常: ' . $pr); break; }
            }
            if ($e['type'] === 'DIE' && !preg_match('/【.+】/u', $e['detail'])) fail($issues, $tag, 'DIE 事件缺翻牌: ' . $e['detail']);
            if ($e['type'] === 'EXILE' && !preg_match('/【.+】/u', $e['detail'])) fail($issues, $tag, 'EXILE 事件缺翻牌: ' . $e['detail']);
        }

        // 视图隔离：观战/死者不得看到未翻牌身份（此处结算前重放一局做检查）
        if ($bi === 2) {
            $g2 = Game::create($board, $seats);
            $i2 = 0;
            while (!$g2->isGameOver() && $i2++ < 300) {
                $k = $g2->currentActionKind(); $s = $g2->currentActor();
                if (!$k || !$s) break;
                ww_apply_auto($g2, $s, ww_auto_action($g2, $s, []));
            }
            // 找一个未出局且非狼的座位视角，检查他人 role 必须为空
            $spect = null;
            foreach ($g2->st['players'] as $pp) if ($pp['alive'] && !$g2->isWolfRole($pp['role'])) { $spect = $pp['seat']; break; }
            if ($spect !== null && !$g2->isGameOver()) { // 结算后全翻牌属预期，跳过泄露检查
                $v = $g2->viewFor($spect, $g2->events());
                foreach ($v['seats'] as $vs) {
                    if ($vs['seat'] === $spect) continue;
                    if ($vs['alive'] && !empty($vs['role'])) fail($issues, "板2泄露", "$spect 号看到了存活 {$vs['seat']} 号身份 {$vs['role']}");
                }
                $vw = $g2->viewFor(0, $g2->events());
                foreach ($vw['seats'] as $vs) if ($vs['alive'] && !empty($vs['role'])) { fail($issues, "板2泄露", "观战看到存活 {$vs['seat']} 号身份"); break; }
            }
        }
    }
}

echo "局数={$stat['games']} 好人胜={$stat['good']} 狼人胜={$stat['wolf']} 最大天数={$stat['maxday']}\n";
echo "自爆={$stat['blowups']} 开枪={$stat['shoots']} 警长={$stat['sheriffs']} PK={$stat['pks']} 解药={$stat['saves']} 毒药={$stat['poisons']} 禁言跳过={$stat['silenced']} 乌鸦={$stat['crowMarks']} 空守={$stat['guardEmpty']}\n";
echo "问题数=" . count($issues) . "\n";
$uniq = array_count_values(array_map(function ($i) { return preg_replace('/板\d+局\d+/', 'TAG', mb_substr($i, 0, 60)); }, $issues));
foreach ($uniq as $k => $c) echo "  x{$c}  $k\n";
exit(count($issues) > 0 ? 1 : 0);
