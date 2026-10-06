import { spawn } from 'node:child_process';
import fs from 'node:fs';
const EDGE = [process.env['ProgramFiles(x86)'], process.env['ProgramFiles'], 'C:/Program Files (x86)']
  .filter(Boolean).map(d => d + '/Microsoft/Edge/Application/msedge.exe').find(fs.existsSync) || 'msedge';
console.log('EDGE =', EDGE, 'exists =', fs.existsSync(EDGE));
const b = spawn(EDGE, ['--headless=new','--remote-debugging-port=9336','--user-data-dir=G:/狼人杀/screenshots/ui-reflow-probe','--no-first-run','--disable-gpu','about:blank'], { stdio: ['ignore','pipe','pipe'] });
b.on('error', e => console.log('SPAWN ERROR', e.message));
b.stderr.on('data', d => console.log('STDERR:', d.toString().slice(0,200)));
for (let i=0;i<15;i++){ await new Promise(r=>setTimeout(r,1000));
  try { const r = await fetch('http://127.0.0.1:9336/json/version'); if (r.ok) { console.log('DEVTOOLS UP after', i+1, 's:', (await r.json()).Browser); b.kill(); process.exit(0); } } catch(e) { if(i%5===0) console.log('waiting', i+1, e.message.slice(0,60)); } }
console.log('NOT UP, killing'); b.kill(); process.exit(1);
