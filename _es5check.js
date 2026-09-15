// 用 acorn 以 ECMAScript 5 解析，列出全部语法错误
const fs = require('fs');
const path = require('path');
const acorn = require('acorn');

const dir = process.argv[2] || '.';
const files = fs.readdirSync(dir).filter(f => f.endsWith('.js')).sort();

let totalErr = 0;
for (const f of files) {
  const src = fs.readFileSync(path.join(dir, f), 'utf8');
  let errs = [];
  // 逐个收集：acorn 遇到第一个错误就抛，所以循环剥离重试
  let work = src;
  let offset = 0;
  for (let i = 0; i < 12; i++) {
    try {
      acorn.parse(work, { ecmaVersion: 5, allowReturnOutsideFunction: true });
      break;
    } catch (e) {
      const pos = offset + (e.pos | 0);
      const line = src.slice(0, pos).split('\n').length;
      const col = pos - (src.lastIndexOf('\n', pos - 1) + 1);
      errs.push({ msg: e.message, line, col, pos });
      // 往后跳一点继续找下一个错误（粗暴但对统计足够）
      const next = pos + 1;
      if (next >= src.length) break;
      // 从错误点附近重开一段，行号已记录，这里只用于继续搜索
      work = '\n'.repeat(0) + src.slice(next);
      offset = next;
    }
  }
  if (errs.length) {
    totalErr += errs.length;
    console.log('### ' + f + '  (' + errs.length + ' 处)');
    for (const e of errs) {
      console.log('   L' + e.line + ':' + e.col + '  ' + e.msg);
      const ls = src.split('\n');
      for (let k = Math.max(0, e.line - 2); k < Math.min(ls.length, e.line + 1); k++) {
        console.log('        ' + (k + 1) + ' | ' + ls[k].slice(0, 160));
      }
    }
  } else {
    console.log('### ' + f + '  OK (ES5 合法)');
  }
}
console.log('\n总计 ' + totalErr + ' 处语法错误');
