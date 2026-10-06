import { spawn } from 'node:child_process';
import fs from 'node:fs';
const EDGE = [process.env['ProgramFiles(x86)'], 'C:/Program Files (x86)'].filter(Boolean).map(d => d + '/Microsoft/Edge/Application/msedge.exe').find(fs.existsSync);
async function one(name, port, extra) {
  const prof = 'G:/狼人杀/screenshots/duo-' + name;
  fs.rmSync(prof, { recursive: true, force: true });
  const b = spawn(EDGE, ['--headless=new', `--remote-debugging-port=${port}`, `--user-data-dir=${prof}`, '--no-first-run', '--disable-gpu', ...extra, 'about:blank'], { stdio: ['ignore','ignore','pipe'] });
  b.stderr.on('data', d => { const t = d.toString().trim(); if (t && !/GroupMarker|fontconfig|WARNING/i.test(t)) console.log(`[${name}]`, t.slice(0,140)); });
  for (let i = 0; i < 20; i++) { await new Promise(r=>setTimeout(r,500));
    try { const r = await fetch(`http://127.0.0.1:${port}/json/version`); if (r.ok) { console.log(`[${name}] UP after ${(i+1)*0.5}s`); return b; } } catch(_){}
  }
  console.log(`[${name}] NOT UP`); return b;
}
const a = await one('A', 9341, ['--use-fake-device-for-media-stream','--use-fake-ui-for-media-stream']);
const bb = await one('B', 9342, ['--use-fake-device-for-media-stream','--use-fake-ui-for-media-stream']);
await new Promise(r=>setTimeout(r,1000));
console.log('A alive?', !a.killed, ' B alive?', !bb.killed);
a.kill(); bb.kill(); process.exit(0);
