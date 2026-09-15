/* ============================================================
 * es5fix —— 把极少量的 ES6 语法降级成 ES5，让老内核（Chrome 39 /
 * Android 5.1 WebView）能解析 G805 的后台脚本。
 *
 * 背景：G805 的 js/service.js 第 315 行写了 `wifi_cur_state,`
 * （ES6 对象字面量简写属性），Chrome 39 会抛
 * "Uncaught SyntaxError: Unexpected token ,"，导致整个文件解析失败，
 * 连带 require 依赖链断裂、Knockout 从不 applyBindings、一个 XHR 都发不出。
 *
 * 本模块只做**保守的、可验证的**降级：
 *   1) 对象字面量里的简写属性   {a}  ->  {a : a}
 *   2) 行首的 let / const       ->  var
 * 其余（箭头函数、模板字符串）一律不动 —— 万一后面还有，靠页面 JS 错误
 * 记录暴露出来，不在这里瞎猜。
 *
 * 纯 ES5 实现，可同时运行在 Node 和 Chrome 39 上。
 * ============================================================ */
(function (root) {
    'use strict';

    var SHORT_RE = /^([ \t]*)([A-Za-z_$][A-Za-z0-9_$]*)([ \t]*)([,}])([ \t]*\r?)$/;
    var LET_RE = /^([ \t]*)(let|const)([ \t]+)(?=[A-Za-z_$[{])/;

    /* 判断一个 `{` 是不是「对象字面量」而不是「语句块」。
     * 看它前面最后一个有意义的字符：
     *   = ( [ , : ?  -> 表达式位置，是对象字面量
     *   return/typeof/in/of/new/delete/void 之后也是
     *   ) } ; 空        -> 语句块
     */
    function isObjectBrace(prevCh, prevWord) {
        if (prevCh === '=' || prevCh === '(' || prevCh === '[' ||
            prevCh === ',' || prevCh === ':' || prevCh === '?' ||
            prevCh === '+' || prevCh === '&' || prevCh === '|' || prevCh === '!') {
            return true;
        }
        if (prevWord === 'return' || prevWord === 'typeof' || prevWord === 'in' ||
            prevWord === 'of' || prevWord === 'new' || prevWord === 'delete' ||
            prevWord === 'void' || prevWord === 'instanceof') {
            return true;
        }
        return false;
    }

    /* 扫描一行的括号变化，维护「最内层未闭合容器」栈。
     * 跳过字符串、行注释、块注释，避免括号出现在字面量里把栈搞错。 */
    function scanBrackets(line, st) {
        var i = 0, n = line.length, ch, next, prevCh, j, word;

        while (i < n) {
            ch = line.charAt(i);

            /* 块注释延续中 */
            if (st.inBlock) {
                j = line.indexOf('*/', i);
                if (j < 0) { return; }
                i = j + 2; st.inBlock = false; continue;
            }

            /* 进入字符串 */
            if (ch === '"' || ch === '\'') {
                var q = ch; i++;
                while (i < n) {
                    ch = line.charAt(i);
                    if (ch === '\\') { i += 2; continue; }
                    if (ch === q) { i++; break; }
                    i++;
                }
                continue;
            }

            /* 注释 */
            if (ch === '/' && line.charAt(i + 1) === '/') { return; }
            if (ch === '/' && line.charAt(i + 1) === '*') {
                j = line.indexOf('*/', i + 2);
                if (j < 0) { st.inBlock = true; return; }
                i = j + 2; continue;
            }

            /* 记录最后一个有意义的字符/单词（给 isObjectBrace 用） */
            if (ch === ' ' || ch === '\t' || ch === '\r') { i++; continue; }

            if (ch === '{' || ch === '[' || ch === '(') {
                var kind;
                if (ch === '(') {
                    kind = 'p';
                } else if (ch === '[') {
                    kind = 'a';
                } else {
                    kind = isObjectBrace(st.prevCh, st.prevWord) ? 'o' : 'b';
                }
                /* 补记 `(` 的内容类型：`({` 这种要靠 prevCh 判断，已由上面处理 */
                st.stack.push(kind);
            } else if (ch === '}' || ch === ']' || ch === ')') {
                if (st.stack.length) { st.stack.pop(); }
            }

            /* 更新 prev */
            if (ch === '"' || ch === '\'' || ch === '/' || ch === '*' ||
                ch === ';' || ch === '=' || ch === ',' || ch === ':' ||
                ch === '?' || ch === '!' || ch === '&' || ch === '|' ||
                ch === '{' || ch === '[' || ch === '(') {
                st.prevCh = ch; st.prevWord = '';
            } else if (/[A-Za-z0-9_$]/.test(ch)) {
                /* 累积单词 */
                var s2 = i;
                while (i < n && /[A-Za-z0-9_$]/.test(line.charAt(i))) { i++; }
                st.prevWord = line.substring(s2, i);
                st.prevCh = line.charAt(i - 1);
                continue;
            } else {
                st.prevCh = ch; st.prevWord = '';
            }
            i++;
        }
    }

    /**
     * @param {string} src 原始 JS 源码
     * @return {{code:string, short:number, vars:number}}
     */
    function es5fix(src) {
        if (!src) { return { code: src, short: 0, vars: 0 }; }

        var lines = src.split('\n');
        var st = { stack: [], prevCh: '', prevWord: '', inBlock: false };
        var shortN = 0, varN = 0, i, line, m, top;

        for (i = 0; i < lines.length; i++) {
            line = lines[i];
            top = st.stack.length ? st.stack[st.stack.length - 1] : '';

            /* 1) 简写属性 —— 只在「最内层确实是对象字面量」时改写 */
            m = SHORT_RE.exec(line);
            if (m && top === 'o') {
                line = m[1] + m[2] + ' : ' + m[2] + m[3] + m[4] + m[5];
                shortN++;
            }

            /* 2) 行首 let / const -> var */
            if (LET_RE.test(line)) {
                line = line.replace(LET_RE, '$1var$3');
                varN++;
            }

            lines[i] = line;
            scanBrackets(line, st);
        }

        return { code: lines.join('\n'), short: shortN, vars: varN };
    }

    root.es5fix = es5fix;

    if (typeof module !== 'undefined' && module.exports) {
        module.exports = { es5fix: es5fix };
    }
})(typeof window !== 'undefined' ? window : globalThis);
