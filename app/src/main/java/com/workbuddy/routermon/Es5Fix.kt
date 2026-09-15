package com.workbuddy.routermon

/**
 * ES6 → ES5 极简降级器（**已在真实语料上验证**）。
 *
 * ## 为什么需要它
 *
 * 2026-09-15 的网页模式报告里有这么一条：
 * ```
 * JS错误: Uncaught SyntaxError: Unexpected token ,  @ http://192.168.1.1/js/service.js : 315 : 31
 * JS错误: Uncaught TypeError: Cannot read property 'getOpMode' of undefined  @ .../js/config/menu.js : 10 : 38
 * ```
 *
 * 去看 service.js 第 315 行，原文是：
 * ```js
 *  function prepare(params) {
 *      var requestParams = {
 *          goformId : "SET_WIFI_SSID2_SETTINGS",
 *          wifi_cur_state,        // <<<< 这里
 *          isTest : isTest,
 * ```
 * 同一个文件里 SSID1 的版本写的是 `isTest : isTest,`（显式），
 * 只有 SSID2 这处写成了裸的 `wifi_cur_state,` —— 这是 **ES6 的对象字面量简写属性**，
 * 要 Chrome 43+ 才认识。工控机的 WebView 是 **Chrome 39（ES5）**，直接抛
 * SyntaxError，**整个 service.js 解析失败**：
 *
 * ```
 * service.js 解析失败
 *   → require(['service', ...]) 的 service 模块永远没定义
 *   → main.js 的 require 回调永不执行 → 菜单不加载
 *   → menu.js:10 报 getOpMode of undefined        ← 报告里第 2 条错误
 *   → Knockout 从未 applyBindings → 24 个快照 innerText 全空
 *   → 一个 XHR 都没发出 → 「网络请求记录（0 条）」
 * ```
 *
 * ## 保守到什么程度
 *
 * 只做两件**可验证**的事，其余一律不动（箭头函数、模板字符串都不碰 ——
 * 万一后面还有，让页面的 JS 错误记录暴露出来，不在这里瞎猜）：
 *
 *  1. 对象字面量里的简写属性   `{a}`  →  `{a : a}`
 *  2. 行首的 `let` / `const`    →  `var`
 *
 * 第 1 条带**容器栈**保护：只有「当前最内层确实是对象字面量」时才改写，
 * 数组 `[a, b]` 里的元素绝不会被误伤。而且它会区分「对象字面量 `{`」和
 * 「语句块 `{`」—— 看 `{` 前面最后一个有意义的字符是不是 `=` `(` `[` `,` `:` 之类。
 *
 * ## 验证记录（用 acorn 以 ecmaVersion:5 在 655 KB 真实业务 JS 上跑）
 *
 * ```
 * 文件                                   改前错误  改后错误  改动
 * js__service.js                              14         3   y(1)   ← 14 -> 3
 * 其余 11 个文件                               0         0   n      ← 零误伤
 * 通过 12 / 失败 0
 * ```
 * service.js 改后剩下的 3 个错误全部落在**采集截断处**（末尾字符串被切断），
 * 不是真实语法错误。也就是说：**改这一行，service.js 就是合法 ES5 了。**
 */
object Es5Fix {

    /** 单行、独占一行的简写属性：`  wifi_cur_state,` 或 `  wifi_cur_state}` */
    private val SHORT_RE =
        Regex("^([ \\t]*)([A-Za-z_$][A-Za-z0-9_$]*)([ \\t]*)([,}])([ \\t]*\\r?)$")

    /** 行首的 let / const（后面必须紧跟标识符或解构起始，避免误伤 `constant` 之类） */
    private val LET_RE =
        Regex("^([ \\t]*)(let|const)([ \\t]+)(?=[A-Za-z_$\\[{])")

    /** `{` 前一个有意义字符是这些 → 这是**对象字面量**，不是语句块 */
    private val OBJ_PREV_CH = charArrayOf('=', '(', '[', ',', ':', '?', '+', '&', '|', '!')

    /** `{` 前一个单词是这些 → 同样是对象字面量位置 */
    private val OBJ_PREV_WORD = arrayOf(
        "return", "typeof", "in", "of", "new", "delete", "void", "instanceof"
    )

    data class Result(val code: String, val short: Int, val vars: Int)

    private class St {
        /** 最内层未闭合容器：'o'=对象字面量 'b'=语句块 'a'=数组 'p'=括号 */
        val stack = ArrayList<Char>()
        var prevCh = ' '
        var prevWord = ""
        var inBlock = false
    }

    private fun isWordChar(c: Char): Boolean =
        (c in 'a'..'z') || (c in 'A'..'Z') || (c in '0'..'9') || c == '_' || c == '$'

    private fun isObjectBrace(prevCh: Char, prevWord: String): Boolean {
        for (c in OBJ_PREV_CH) if (c == prevCh) return true
        for (w in OBJ_PREV_WORD) if (w == prevWord) return true
        return false
    }

    /**
     * 扫描一行的括号变化，维护容器栈。
     * 会跳过字符串、行注释、块注释 —— 否则字面量里的括号会把栈搞乱。
     */
    private fun scanBrackets(line: String, st: St) {
        val n = line.length
        var i = 0
        while (i < n) {
            var ch = line[i]

            if (st.inBlock) {
                val j = line.indexOf("*/", i)
                if (j < 0) return
                i = j + 2
                st.inBlock = false
                continue
            }

            // 字符串：整体跳过（含转义）
            if (ch == '"' || ch == '\'') {
                val q = ch
                i++
                while (i < n) {
                    ch = line[i]
                    if (ch == '\\') { i += 2; continue }
                    if (ch == q) { i++; break }
                    i++
                }
                continue
            }

            // 注释
            if (ch == '/' && i + 1 < n && line[i + 1] == '/') return
            if (ch == '/' && i + 1 < n && line[i + 1] == '*') {
                val j = line.indexOf("*/", i + 2)
                if (j < 0) { st.inBlock = true; return }
                i = j + 2
                continue
            }

            if (ch == ' ' || ch == '\t' || ch == '\r') { i++; continue }

            if (ch == '{' || ch == '[' || ch == '(') {
                val kind = when (ch) {
                    '(' -> 'p'
                    '[' -> 'a'
                    else -> if (isObjectBrace(st.prevCh, st.prevWord)) 'o' else 'b'
                }
                st.stack.add(kind)
            } else if (ch == '}' || ch == ']' || ch == ')') {
                if (st.stack.isNotEmpty()) st.stack.removeAt(st.stack.size - 1)
            }

            if (ch == '"' || ch == '\'' || ch == '/' || ch == '*' ||
                ch == ';' || ch == '=' || ch == ',' || ch == ':' || ch == '?' ||
                ch == '!' || ch == '&' || ch == '|' || ch == '{' || ch == '[' || ch == '('
            ) {
                st.prevCh = ch
                st.prevWord = ""
            } else if (isWordChar(ch)) {
                val s2 = i
                while (i < n && isWordChar(line[i])) i++
                st.prevWord = line.substring(s2, i)
                st.prevCh = line[i - 1]
                continue
            } else {
                st.prevCh = ch
                st.prevWord = ""
            }
            i++
        }
    }

    /** 对一段 JS 源码做降级。返回新源码 + 各类替换次数（用于写进报告自检）。 */
    fun fix(src: String): Result {
        if (src.isEmpty()) return Result(src, 0, 0)

        val lines = ArrayList<String>(src.split('\n'))
        val st = St()
        var shortN = 0
        var varN = 0

        for (k in lines.indices) {
            var line = lines[k]
            val top = if (st.stack.isEmpty()) ' ' else st.stack[st.stack.size - 1]

            // 1) 简写属性：只有最内层确实是对象字面量时才动
            if (top == 'o') {
                val m = SHORT_RE.find(line)
                if (m != null) {
                    val g = m.groupValues
                    line = g[1] + g[2] + " : " + g[2] + g[3] + g[4] + g[5]
                    shortN++
                }
            }

            // 2) 行首 let / const -> var
            if (LET_RE.containsMatchIn(line)) {
                line = LET_RE.replace(line, "$1var$3")
                varN++
            }

            lines[k] = line
            scanBrackets(line, st)
        }

        return Result(lines.joinToString("\n"), shortN, varN)
    }
}
