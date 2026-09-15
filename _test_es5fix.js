// 验证 es5fix：对每个已捕获的 JS，
//   1) 改前 / 改后都用 acorn(ecmaVersion:5) 解析，比较错误数量
//   2) 确保「本来 ES5 合法的文件」在改后逐字节不变（零误伤）
const fs = require('fs');
const path = require('path');
const acorn = require('acorn');
const { es5fix } = require('./_es5fix.js');

const dir = process.argv[2] || '_js';
const files = fs.readdirSync(dir).filter(f => f.endsWith('.js')).sort();

function countErrors(src) {
  let work = src, off = 0, n = 0, first = null;
  for (let k = 0; k < 30; k++) {
    try { acorn.parse(work, { ecmaVersion: 5, allowReturnOutsideFunction: true }); break; }
    catch (e) {
      const pos = off + (e.pos | 0);
      const line = src.slice(0, pos).split('\n').length;
      const col = pos - (src.lastIndexOf('\n', pos - 1) + 1);
      if (!first) first = { msg: e.message, line, col };
      n++;
      if (pos + 1 >= src.length) break;
      work = src.slice(pos + 1); off = pos + 1;
    }
  }
  return { n, first };
}

let pass = 0, fail = 0, changed = 0, unchanged = 0, totalFix = 0;
console.log('文件                                   改前错误  改后错误  改动  修复点');
console.log('-'.repeat(78));
for (const f of files) {
  const src = fs.readFileSync(path.join(dir, f), 'utf8');
  const r = es5fix(src);
  const before = countErrors(src);
  const after = countErrors(r.code);
  const nFix = r.short + r.vars;
  totalFix += nFix;
  const diff = (r.code !== src);
  if (diff) changed++; else unchanged++;

  // 零误伤检查：源码本来 ES5 合法，就应该一个字符都不改
  let verdict = '';
  if (before.n === 0 && diff) { verdict = '  <<< 误伤!!'; fail++; }
  else if (after.n < before.n) { verdict = '  <<< 修复生效'; pass++; }
  else if (after.n === before.n) { pass++; }
  else { verdict = '  <<< 改坏了!!'; fail++; }

  console.log(
    f.padEnd(36) + String(before.n).padStart(7) + String(after.n).padStart(10) +
    String(diff ? ('y(' + nFix + ')') : 'n').padStart(8) + verdict
  );
  if (nFix) {
    console.log('      修复明细: 简写属性 ' + r.short + ' 处, let/const ' + r.vars + ' 处');
    if (before.first) console.log('      改前首个错误: L' + before.first.line + ':' + before.first.col + ' ' + before.first.msg);
    if (after.first) console.log('      改后首个错误: L' + after.first.line + ':' + after.first.col + ' ' + after.first.msg);
  }
}
console.log('-'.repeat(78));
console.log('文件数 ' + files.length + ' | 有改动 ' + changed + ' | 无改动 ' + unchanged +
  ' | 修复点合计 ' + totalFix + ' | 通过 ' + pass + ' | 失败 ' + fail);
