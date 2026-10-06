import fs from 'fs';
const base = 'G:/狼人杀/downloads';
const mf = JSON.parse(fs.readFileSync(base + '/manifest.json', 'utf8'));
const actual = fs.readdirSync(base).filter(f => f !== 'manifest.json');
console.log('MANIFEST_ITEMS=' + mf.items.length);
let problems = 0;
const referenced = new Set();
for (const it of mf.items) {
  if (!it.file) { console.log('skip(no-file) platform=' + it.platform); continue; }
  referenced.add(it.file);
  const p = base + '/' + it.file;
  const exists = fs.existsSync(p);
  let size = exists ? fs.statSync(p).size : -1;
  const declaredSize = it.size; // manifest may not carry size; served API added it
  const versionInName = (it.file.match(/v?(\d+\.\d+\.\d+)/) || [])[1];
  const ok = exists;
  if (!ok) problems++;
  console.log(
    'platform=' + it.platform +
    ' ver=' + JSON.stringify(it.version) +
    ' file=' + it.file +
    ' exists=' + exists +
    ' bytes=' + size +
    ' nameVersion=' + versionInName +
    ' versionMatch=' + (versionInName === (it.version || '')) +
    (ok ? ' OK' : ' <<< MISSING'));
}
console.log('--- ORPHAN files (present in dir, not referenced by manifest) ---');
for (const f of actual) {
  if (!referenced.has(f)) console.log('ORPHAN ' + f + ' (' + fs.statSync(base + '/' + f).size + ' bytes)');
}
console.log('MISSING_REFERENCED_COUNT=' + problems);
