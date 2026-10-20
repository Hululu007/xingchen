/* 星辰 FreeJS(CommonJS) 垫片 v1
 * 给 MusicFree 插件提供运行环境：
 *  - CommonJS：require / module / exports（最小实现）
 *  - 内建模块：axios（最小实现）、he、dayjs（最小实现）
 *  - crypto-js / cheerio：由宿主把库原文包进 __freejsDefine 注入（见下）
 *  - fetch：最小实现，返回 thenable Response（.text()/.json() 也是 thenable）
 *  - console：转宿主 __freejsLog
 *
 * 网络统一经宿主函数 __freejsRequest(method, url, optsJson) 同步发出，
 * 返回 JSON 字符串 {status, headers, text}；失败抛 Error。
 * 宿主侧（Android 走壳子 OkHttp，吃代理/DoH/节点定向；试验台走 curl）。
 *
 * 语义约定（宿主 Java 桥与试验台必须一致）：
 *  1. require 只认内建模块表，不在表里的抛错；插件不得 require 相对路径。
 *  2. axios.get/post 返回 thenable，resolve {data, status, headers}；
 *     data 在 content-type 含 json 时自动 JSON.parse，否则原文。
 *  3. fetch 返回 thenable<response>；response.text()/.json() 返回 thenable。
 *  4. 插件顶层用 module.exports 导出；宿主读 globalThis.module.exports。
 */
(function (global) {
    "use strict";

    /* ---------- 0. console ---------- */
    if (!global.console) {
        var __log = function (lv, args) {
            var msg = Array.prototype.map.call(args, function (a) {
                try { return typeof a === "string" ? a : JSON.stringify(a); } catch (e) { return String(a); }
            }).join(" ");
            if (typeof global.__freejsLog === "function") { try { global.__freejsLog(lv, msg); } catch (e) { /* 忽略 */ } }
        };
        global.console = {
            log: function () { __log("I", arguments); },
            info: function () { __log("I", arguments); },
            debug: function () { __log("D", arguments); },
            warn: function () { __log("W", arguments); },
            error: function () { __log("E", arguments); }
        };
    }

    /* ---------- 1. CommonJS 最小实现 ---------- */
    var __factories = {};
    var __cache = {};

    function __defineBuiltin(name, factory) {
        __factories[name] = factory;
        delete __cache[name];
    }

    function require(name) {
        if (Object.prototype.hasOwnProperty.call(__cache, name)) return __cache[name];
        var factory = __factories[name];
        if (!factory) throw new Error("[freejs] 找不到内建模块: " + name);
        var m = { exports: {} };
        factory(require, m, m.exports);
        if (m.exports && m.exports["default"] === undefined && typeof m.exports === "object") {
            /* 编译插件常取 .default，顺手挂上自指 */
            try { m.exports["default"] = m.exports; } catch (e) { /* 忽略 */ }
        }
        __cache[name] = m.exports;
        return m.exports;
    }

    var module = { exports: {} };
    global.require = require;
    global.module = module;
    global.exports = module.exports;
    /* 宿主用它注入 crypto-js / cheerio 等库原文 */
    global.__freejsDefine = __defineBuiltin;

    /* ---------- 2. 同步请求桥 ---------- */
    function __nativeRequest(method, url, opts) {
        if (typeof global.__freejsRequest !== "function") {
            throw new Error("[freejs] 宿主未提供 __freejsRequest");
        }
        var raw = global.__freejsRequest(method, url, JSON.stringify(opts || {}));
        var res = JSON.parse(raw);
        if (res && res.error) throw new Error("[freejs] 请求失败: " + res.error);
        return res;
    }

    function __buildUrl(url, params) {
        if (!params) return url;
        var qs = [];
        for (var k in params) {
            if (!Object.prototype.hasOwnProperty.call(params, k)) continue;
            var v = params[k];
            if (v === undefined || v === null) continue;
            qs.push(encodeURIComponent(k) + "=" + encodeURIComponent(v));
        }
        if (!qs.length) return url;
        return url + (url.indexOf("?") >= 0 ? "&" : "?") + qs.join("&");
    }

    function __contentTypeOf(res) {
        var headers = res.headers || {};
        for (var k in headers) {
            if (k.toLowerCase() === "content-type") return headers[k] || "";
        }
        return "";
    }

    function __parseData(res) {
        var ct = __contentTypeOf(res);
        if (ct.indexOf("json") >= 0) {
            try { return JSON.parse(res.text); } catch (e) { return res.text; }
        }
        /* 真 axios 默认对字符串响应也尝试 JSON.parse（forcedJSONParsing），
         * 不少接口（如酷我 r.s）回 text/plain 的 JSON 文本，插件按对象取 data.abslist */
        if (typeof res.text === "string") {
            var t = res.text.replace(/^\s+/, "");
            if (t.charAt(0) === "{" || t.charAt(0) === "[") {
                try { return JSON.parse(res.text); } catch (e) { /* 不是 JSON 就原文 */ }
            }
        }
        return res.text;
    }

    function __thenable(value) {
        return {
            then: function (onOk, onErr) {
                try {
                    return onOk ? onOk(value) : value;
                } catch (e) {
                    if (onErr) return onErr(e);
                    throw e;
                }
            },
            "catch": function (onErr) { return this.then(null, onErr); }
        };
    }

    function __rejected(err) {
        return {
            then: function (onOk, onErr) {
                if (onErr) return onErr(err);
                throw err;
            },
            "catch": function (onErr) { return onErr(err); }
        };
    }

    /* ---------- 3. fetch（最小实现） ---------- */
    function __makeResponse(res) {
        var lower = {};
        var headers = res.headers || {};
        for (var k in headers) lower[String(k).toLowerCase()] = headers[k];
        return {
            status: res.status,
            ok: res.status >= 200 && res.status < 300,
            headers: {
                get: function (k) {
                    var v = lower[String(k).toLowerCase()];
                    return v === undefined ? null : v;
                }
            },
            text: function () { return __thenable(res.text); },
            json: function () {
                try { return __thenable(JSON.parse(res.text)); }
                catch (e) { return __rejected(e); }
            }
        };
    }

    function fetch(url, opts) {
        opts = opts || {};
        try {
            var res = __nativeRequest(opts.method || "GET", url, {
                headers: opts.headers || {},
                body: opts.body,
                timeout: opts.timeout || 20000
            });
            return __thenable(__makeResponse(res));
        } catch (e) {
            return __rejected(e);
        }
    }
    global.fetch = fetch;

    /* ---------- 4. axios（最小实现） ---------- */
    function __axiosRequest(method, url, opts) {
        opts = opts || {};
        var full = __buildUrl(url, opts.params);
        var reqOpts = { headers: opts.headers || {}, timeout: opts.timeout || 20000 };
        if (opts.data !== undefined && opts.data !== null) {
            reqOpts.body = (typeof opts.data === "string") ? opts.data : JSON.stringify(opts.data);
            var hasCt = false;
            for (var k in reqOpts.headers) { if (k.toLowerCase() === "content-type") { hasCt = true; break; } }
            if (!hasCt) reqOpts.headers["Content-Type"] = "application/json";
        }
        var res;
        try {
            res = __nativeRequest(method, full, reqOpts);
        } catch (e) {
            return __rejected(e);
        }
        if (res.status < 200 || res.status >= 300) {
            var err = new Error("[freejs] axios 请求返回 " + res.status);
            err.response = { data: __parseData(res), status: res.status, headers: res.headers };
            return __rejected(err);
        }
        return __thenable({ data: __parseData(res), status: res.status, headers: res.headers, config: opts });
    }

    /* 真 axios 本身是可调用函数（axios({method,url,...})），TS 编译的插件常走
     * (0, axios_1.default)({...}) 这条路；做成函数对象并自挂 default，两条路都通 */
    var axios = function (config) { config = config || {}; return __axiosRequest(config.method || "GET", config.url, config); };
    axios.get = function (url, opts) { return __axiosRequest("GET", url, opts); };
    axios.post = function (url, data, opts) { opts = opts || {}; opts.data = data; return __axiosRequest("POST", url, opts); };
    axios.put = function (url, data, opts) { opts = opts || {}; opts.data = data; return __axiosRequest("PUT", url, opts); };
    axios.head = function (url, opts) { return __axiosRequest("HEAD", url, opts); };
    axios["delete"] = function (url, opts) { return __axiosRequest("DELETE", url, opts); };
    axios.request = function (opts) { opts = opts || {}; return __axiosRequest(opts.method || "GET", opts.url, opts); };
    axios.create = function () { return axios; };
    axios["default"] = axios;
    __defineBuiltin("axios", function (require, module) { module.exports = axios; });

    /* ---------- 5. he（最小实现：只做 decode/encode 常用实体） ---------- */
    __defineBuiltin("he", function (require, module) {
        var NAMED = { amp: "&", lt: "<", gt: ">", quot: "\"", apos: "'", nbsp: " " };
        function decode(s) {
            if (s === undefined || s === null) return s;
            return String(s).replace(/&(#x[0-9a-fA-F]+|#\d+|[a-zA-Z][a-zA-Z0-9]+);/g, function (m, e) {
                if (e.charAt(0) === "#") {
                    var c = e.charAt(1).toLowerCase() === "x" ? parseInt(e.slice(2), 16) : parseInt(e.slice(1), 10);
                    return isNaN(c) ? m : String.fromCharCode(c);
                }
                return NAMED[e] !== undefined ? NAMED[e] : m;
            });
        }
        function encode(s) {
            if (s === undefined || s === null) return s;
            return String(s).replace(/[&<>"']/g, function (c) {
                return { "&": "&amp;", "<": "&lt;", ">": "&gt;", "\"": "&quot;", "'": "&#39;" }[c];
            });
        }
        module.exports = { decode: decode, encode: encode };
    });

    /* ---------- 6. dayjs（最小实现：unix/format/valueOf） ---------- */
    __defineBuiltin("dayjs", function (require, module) {
        function pad(n, l) { n = String(n); while (n.length < (l || 2)) n = "0" + n; return n; }
        function Dayjs(t) { this.t = (t === undefined || t === null) ? Date.now() : t; }
        Dayjs.prototype.format = function (fmt) {
            var d = new Date(this.t);
            var map = {
                YYYY: d.getFullYear(), MM: pad(d.getMonth() + 1), DD: pad(d.getDate()),
                HH: pad(d.getHours()), mm: pad(d.getMinutes()), ss: pad(d.getSeconds())
            };
            return String(fmt).replace(/YYYY|MM|DD|HH|mm|ss/g, function (m) { return map[m]; });
        };
        Dayjs.prototype.valueOf = function () { return this.t; };
        Dayjs.prototype.toDate = function () { return new Date(this.t); };
        function dayjs(a) {
            if (a === undefined) return new Dayjs();
            if (typeof a === "number") return new Dayjs(a);
            var t = Date.parse(a);
            return new Dayjs(isNaN(t) ? Date.now() : t);
        }
        dayjs.unix = function (s) { return new Dayjs(s * 1000); };
        module.exports = dayjs;
    });

    /* ---------- 7. crypto-js / cheerio 由宿主注入 ----------
     * Java 桥（或试验台）在加载插件前执行：
     *   __freejsDefine("crypto-js", function (require, module, exports) { <crypto-js.js 全文> });
     *   __freejsDefine("cheerio",  function (require, module, exports) { <cheerio.cjs.js 全文> });
     * 两个库都是 UMD/CJS 形态，包进来后自动挂 module.exports。
     */

    global.__freejsShimVersion = 1;
})(globalThis);
