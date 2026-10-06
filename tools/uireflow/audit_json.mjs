import fs from 'fs';

const p = 'G:/狼人杀/src/main/resources/static/data/changelog.json';
let raw = fs.readFileSync(p, 'utf8');
let obj;
try {
  obj = JSON.parse(raw);
  console.log('JSON_VALID=true');
} catch (e) {
  console.log('JSON_VALID=false', e.message);
  process.exit(1);
}
const items = obj.items || [];
console.log('UPDATED=' + obj.updated);
console.log('ITEMS_COUNT=' + items.length);
console.log('ITEMS_NONEMPTY=' + (items.length > 0));

// exact duplicate full-text check
const seen = new Map();
for (let i = 0; i < items.length; i++) {
  const t = (items[i].text || '').trim();
  seen.set(t, (seen.get(t) || []).concat(i));
}
let dup = false;
for (const [t, idxs] of seen) {
  if (idxs.length > 1) {
    dup = true;
    console.log('EXACT_DUPLICATE count=' + idxs.length + ' indices=[' + idxs.join(',') + '] text=' + JSON.stringify(t.slice(0, 40)));
  }
}
console.log('HAS_EXACT_DUPLICATE=' + dup);

// "跨平台客户端 v2.0.0" occurrences
const kw = '跨平台客户端 v2.0.0';
const kwHits = items.map((it, i) => ({ i, t: it.text || '' })).filter(x => x.t.includes(kw));
console.log('CROSSPLATFORM_v2_COUNT=' + kwHits.length);
for (const h of kwHits) console.log('  v2 index=' + h.i + ' :: ' + h.t.slice(0, 60));

// near-duplicate detection (token overlap > 0.8) for consecutive
function sim(a, b) {
  const A = new Set(a), B = new Set(b);
  let inter = 0;
  for (const c of A) if (B.has(c)) inter++;
  return inter / Math.max(A.size, B.size);
}
console.log('--- FIRST 8 ENTRIES (newest) ---');
items.slice(0, 8).forEach((it, i) => console.log(i + ' [' + it.date + '][' + it.tag + '] ' + it.text.slice(0, 50)));
