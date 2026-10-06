import { spawn } from 'node:child_process';
import fs from 'node:fs';
const EDGE = [process.env['ProgramFiles(x86)'], 'C:/Program Files (x86)'].filter(Boolean).map(d => d + '/Microsoft/Edge/Application/msedge.exe').find(fs.existsSync);
const b = spawn(EDGE, ['--headless=new','--remote-debugging-port=9337','--user-data-dir=G:/狼人杀/screenshots/ui-reflow-probe2','--no-first-run','--disable-gpu','--hide-scrollbars','--ignore-certificate-errors','about:blank'], { stdio:['ignore','ignore','pipe'] });
b.stderr.on('data', d => { const s=d.toString().trim(); if(s) console.log('STDERR:', s.slice(0,160)); });
await new Promise(r=>setTimeout(r,3000));
for (const ep of ['/json/version','/json/list','/json']) {
  try { const r = await fetch('http://127.0.0.1:9337'+ep); const t = await r.text(); console.log(ep, '→', r.status, t.slice(0,120).replace(/\s+/g,' ')); }
  catch(e){ console.log(ep, '→ ERR', e.message.slice(0,120)); }
}
b.kill(); process.exit(0);
