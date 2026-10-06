// tools/uireflow/shot3.mjs —— 用确定性状态渲染“投票实时条 / 记录筛选 / 结算大事记”，避免秒过相位抓不到帧
process.env.NODE_TLS_REJECT_UNAUTHORIZED = '0';
import { spawn } from 'node:child_process';
import fs from 'node:fs';
import path from 'node:path';
const ROOT='G:/狼人杀';
const ORIGIN='https://127.0.0.1:11111', PORT=9335;
const EDGE = path.join('C:', 'Program Files (x86)', 'Microsoft', 'Edge', 'Application', 'msedge.exe');
const OUT=path.join(ROOT,'screenshots','ui-reflow'), PROFILE=path.join(ROOT,'screenshots','ui-reflow-profile3');
fs.rmSync(PROFILE,{recursive:true,force:true}); fs.mkdirSync(OUT,{recursive:true});
const sleep=ms=>new Promise(r=>setTimeout(r,ms)); const errors=[];
const browser=spawn(EDGE,['--headless=new',`--remote-debugging-port=${PORT}`,`--user-data-dir=${PROFILE}`,'--no-first-run','--disable-gpu','--hide-scrollbars','--ignore-certificate-errors','about:blank'],{stdio:'ignore'});
let ws,mid=0;const pending=new Map();const events=[];
function cmd(m,p={}){const id=++mid;return new Promise((res,rej)=>{pending.set(id,{res,rej});ws.send(JSON.stringify({id,method:m,params:p}));setTimeout(()=>{if(pending.has(id)){pending.delete(id);rej(new Error('超时 '+m));}},30000);});}
async function connect(){let t=null;for(let i=0;i<80;i++){try{const r=await fetch(`http://127.0.0.1:${PORT}/json/list`);if(r.ok){t=await r.json();break;}}catch(_){}await sleep(400);}
 const pg=t.find(x=>x.type==='page');ws=new WebSocket(pg.webSocketDebuggerUrl);await new Promise((r,j)=>{ws.onopen=r;ws.onerror=j;});
 ws.onmessage=ev=>{const m=JSON.parse(ev.data);if(m.id&&pending.has(m.id)){const q=pending.get(m.id);pending.delete(m.id);m.error?q.rej(new Error(JSON.stringify(m.error))):q.res(m.result);}else if(m.method){events.push(m);
  if(m.method==='Runtime.exceptionThrown')errors.push('JS异常: '+(m.params.exceptionDetails.exception?.description||'').split('\n')[0]);
  if(m.method==='Runtime.consoleAPICalled'&&m.params.type==='error')errors.push('console.error: '+(m.params.args||[]).map(a=>a.value??a.description??'').join(' ').slice(0,160));}};
 await cmd('Page.enable');await cmd('Runtime.enable');await cmd('Log.enable');}
async function ev(e){const r=await cmd('Runtime.evaluate',{expression:e,returnByValue:true,awaitPromise:true});if(r.exceptionDetails)throw new Error(r.exceptionDetails.exception?.description||r.exceptionDetails.text);return r.result.value;}
async function goto(u){events.length=0;await cmd('Page.navigate',{url:u});for(let i=0;i<60;i++){if(events.some(e=>e.method==='Page.loadEventFired'))break;await sleep(250);}await sleep(900);}
async function shot(n){const {data}=await cmd('Page.captureScreenshot',{format:'png'});fs.writeFileSync(path.join(OUT,n+'.png'),Buffer.from(data,'base64'));console.log('[v3] 📸 '+n);}
let TOKEN='';
async function api(p,m='GET',b=null){const r=await fetch(ORIGIN+p,{method:m,headers:{'Content-Type':'application/json',...(TOKEN?{Authorization:'Bearer '+TOKEN}:{})},body:b?JSON.stringify(b):null});const t=await r.text();if(!r.ok)throw new Error(p+' '+r.status);try{return JSON.parse(t);}catch(_){return t;}}
const FEED = JSON.stringify([
 {day:1,type:'DAWN_ANNOUNCE',actor:0,target:0,detail:'存活玩家：1号 2号 3号 4号 5号 6号'},
 {day:1,type:'SPEECH',actor:2,target:0,detail:'我是好人，先听后置位。'},
 {day:1,type:'SPEECH',actor:3,target:0,detail:'我怀疑4号，他昨晚一直没表态。'},
 {day:1,type:'VOTE_DETAIL',actor:0,target:0,detail:'放逐投票 1→2号  3→2号  4→5号  5→2号  6→弃'},
 {day:1,type:'VOTE_RESULT',actor:2,target:0,detail:'得票最多，被放逐'},
 {day:1,type:'PLAYER_DIED',actor:2,target:0,detail:'EXILE'},
 {day:1,type:'NIGHT_ACTION',actor:4,target:5,detail:'WOLF_VOTE'},
]);
async function main(){
 await connect(); await cmd('Emulation.setDeviceMetricsOverride',{width:1440,height:900,deviceScaleFactor:1,mobile:false});
 const u='ui_v3_'+Date.now().toString(36).slice(-5);
 TOKEN=(await api('/api/auth/register','POST',{username:u,password:'ui123456',nickname:'渲染验收'})).token;
 await goto(ORIGIN+'/'); await ev(`localStorage.setItem('ww_token','${TOKEN}');localStorage.setItem('ww_guide','done');'ok'`); await goto(ORIGIN+'/'); await sleep(2200);
 await ev(`document.getElementById('btn-create-room').click();'ok'`); await sleep(1500);
 // 造一个“正在放逐投票”的视图状态，直接驱动渲染函数
 await ev(`game.view={roomNo:'900001',phase:'DAY_VOTE',day:1,actionKind:'VOTE',myTurn:true,mySeat:1,
   seats:[1,2,3,4,5,6].map(s=>({seat:s,nickname:['外观验收','暮色','幽兰','赤瞳','雾隐','孤星'][s-1],alive:s!==2,userId:-s,avatarId:s})),
   feed:${FEED},winner:null,myInfo:{role:'村民',faction:'GOOD'}};
   renderGame(game.view);__WW_UX.renderVoteBar();'ok'`);
 await sleep(600); await shot('80-投票条-确定性');
 await ev(`document.querySelector('[data-panel="#dock-feed"]')?.click();__WW_UX.feedFilter.cat='vote';__WW_UX.applyFeedFilter();'ok'`); await sleep(400);
 await shot('81-记录筛选-只看投票');
 await ev(`__WW_UX.feedFilter.cat='death';__WW_UX.applyFeedFilter();'ok'`); await sleep(300); await shot('82-记录筛选-只看出局');
 await ev(`__WW_UX.feedFilter={cat:'all',seat:0};__WW_UX.applyFeedFilter();
   document.getElementById('modal-settle').classList.remove('hidden');
   document.getElementById('settle-winner').textContent='胜利方：狼人阵营';__WW_UX.renderSettleDigest();'ok'`); await sleep(400);
 await shot('83-结算大事记-确定性');
 console.log('[v3] 报错条数 '+errors.length); if(errors.length)console.log(errors.join('\n'));
}
main().catch(e=>{console.error('[v3] 失败:',e);process.exitCode=1;}).finally(()=>{try{ws&&ws.close();}catch(_){}browser.kill();process.exit(process.exitCode||0);});
