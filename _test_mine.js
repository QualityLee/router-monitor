// 验证 v1.4 新加的接口挖掘正则能真的从真实 JS 里挖到东西。
// 这三个正则与 Kotlin 侧逐字对应（RouterClient / WebViewActivity）。
// 运行: node _test_mine.js _js
const fs = require('fs');
const path = require('path');

const CMD_RE = /(?<!\w)["']?cmd["']?\s*[:=]\s*["']([^"']{1,300})["']/g;
const GOFORM_RE = /(?<!\w)["']?goformId["']?\s*[:=]\s*["']([^"']{1,300})["']/g;
const PATH_RE = /["'](\/(?:reqproc|goform|cgi-bin)\/[A-Za-z0-9_\-]{1,40})["']/g;

const dir = process.argv[2] || '_js';
const cmds = new Set(), forms = new Set(), paths = new Set();
let files = 0, bytes = 0;

for (const f of fs.readdirSync(dir)) {
  if (!f.endsWith('.js')) continue;
  const s = fs.readFileSync(path.join(dir, f), 'utf8');
  files++; bytes += s.length;
  for (const m of s.matchAll(CMD_RE)) cmds.add(m[1]);
  for (const m of s.matchAll(GOFORM_RE)) forms.add(m[1]);
  for (const m of s.matchAll(PATH_RE)) paths.add(m[1]);
}

console.log('语料: ' + files + ' 个文件 / ' + bytes + ' 字节');
console.log('接口路径 (' + paths.size + '): ' + [...paths].join('  '));
console.log('cmd 取值 (' + cmds.size + '):');
[...cmds].slice(0, 60).forEach(c => console.log('  · ' + JSON.stringify(c)));
console.log('goformId 取值 (' + forms.size + '): ' + [...forms].join(', '));

// 关键断言：必须能挖到已知存在的三条 cmd
const want = ['AuthMode,passPhrase', 'lan_station_list', 'm_netselect_status', 'station_list'];
const missing = want.filter(w => ![...cmds].some(c => c.includes(w)));
console.log('');
if (missing.length === 0) {
  console.log('✓ 已知 cmd 全部挖到（' + want.length + '/' + want.length + '）');
} else {
  console.log('✗ 漏了: ' + missing.join(', '));
}
