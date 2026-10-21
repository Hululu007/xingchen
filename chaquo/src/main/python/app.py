import hashlib
import os
import requests
import json
import textwrap

for _k in ('http_proxy', 'https_proxy', 'HTTP_PROXY', 'HTTPS_PROXY', 'all_proxy', 'ALL_PROXY'):
    os.environ.pop(_k, None)


def _install_dns_bridge():
    try:
        import socket as _sock
        from com.github.catvod.net import OkHttp as _java_okhttp
        _orig = _sock.getaddrinfo

        def _patched(host, port, family=0, type=0, proto=0, flags=0):
            if isinstance(host, str):
                try:
                    result = []
                    for addr in _java_okhttp.dns().lookup(host):
                        ip = addr.getHostAddress()
                        af = _sock.AF_INET6 if ':' in ip else _sock.AF_INET
                        if family and family != af:
                            continue
                        st = type if type else _sock.SOCK_STREAM
                        sa = (ip, port, 0, 0) if af == _sock.AF_INET6 else (ip, port)
                        result.append((af, st, proto, '', sa))
                    if result:
                        return result
                except Exception:
                    pass
            return _orig(host, port, family, type, proto, flags)

        _sock.getaddrinfo = _patched
    except Exception as e:
        print('dns bridge failed: %s' % e)


_install_dns_bridge()


_file_hashes = {}


# 首页加载代次（修 3）：Java 侧每次作废旧首页加载时代次 +1 并经 set_home_generation
# 推送到这里。homeContent / homeVideoContent 入口用调用方携带的代次校验，
# 过期直接返回空 JSON，不再进脚本。gen 为 None 或负数表示不校验（兼容老调用）。
_home_generation = 0
_EMPTY_HOME_JSON = '{"list":[]}'


def set_home_generation(gen):
    global _home_generation
    try:
        gen = int(gen)
    except Exception:
        return
    if gen > _home_generation:
        _home_generation = gen


def _home_gen_ok(gen):
    try:
        return gen is None or int(gen) < 0 or int(gen) >= _home_generation
    except Exception:
        return True


def _resolve_pic(spider_obj, pic):
    if not pic or not isinstance(pic, str):
        return pic
    if pic.startswith('http://') or pic.startswith('https://'):
        return pic
    if pic.startswith('//'):
        return 'https:' + pic
    if pic.startswith('/'):
        base = ''
        for attr in ('site', 'host', 'base_url', 'baseUrl', 'domain'):
            try:
                v = getattr(spider_obj, attr, '')
                if v and isinstance(v, str) and v.startswith('http'):
                    base = v.rstrip('/')
                    break
            except Exception:
                pass
        if base:
            return base + pic
    return pic


def _fix_pics(spider_obj, result):
    try:
        if isinstance(result, dict):
            items = result.get('list')
            if isinstance(items, list):
                for it in items:
                    if isinstance(it, dict) and 'vod_pic' in it:
                        it['vod_pic'] = _resolve_pic(spider_obj, it.get('vod_pic', ''))
    except Exception:
        pass
    return result


def spider(cache, source, file_name=None):
    name = file_name or "spider.py"
    if len(name) > 100 or "\n" in name or "class Spider" in name:
        name = "spider.py"
    if not name.endswith('.py'):
        name = name + '.py'
    path = cache + '/' + name
    data = textwrap.dedent(source).encode('utf-8')
    digest = hashlib.sha256(data).hexdigest()
    if _file_hashes.get(name) != digest:
        writeFile(path, data)
        _file_hashes[name] = digest
    mod_name = name.split('.')[0]
    from importlib.machinery import SourceFileLoader
    return SourceFileLoader(mod_name, path).load_module().Spider()


def download(path, api):
    if api.startswith('http'):
        length = None
        try:
            head = requests.head(api, allow_redirects=True, verify=False)
            ok = head is not None and 200 <= head.status_code < 300
            raw = head.headers.get('Content-Length') if ok else None
            length = int(raw) if raw is not None else None
        except Exception:
            length = None
        if length is not None and os.path.exists(path) and os.path.getsize(path) == length:
            return 'skipped-length'
        try:
            data = redirect(api).content
        except Exception:
            if os.path.exists(path) and os.path.getsize(path) > 0:
                return 'cached-offline'
            raise
    else:
        data = textwrap.dedent(api).encode('utf-8')
    if os.path.exists(path):
        try:
            with open(path, 'rb') as f:
                old = f.read()
        except Exception:
            old = None
        if old == data:
            return 'skipped-hash'
    tmp = path + '.tmp'
    with open(tmp, 'wb') as f:
        f.write(data)
    os.replace(tmp, path)
    return 'updated'


def writeFile(path, content):
    with open(path, 'wb') as f:
        f.write(content)


def redirect(url):
    rsp = requests.get(url, allow_redirects=False, verify=False)
    if 'Location' in rsp.headers:
        return redirect(rsp.headers['Location'])
    else:
        return rsp


def str2json(content):
    if isinstance(content, str):
        return json.loads(content)
    return content


def getDependence(ru):
    fn = getattr(ru, "getDependence", None)
    return fn() if callable(fn) else []


def getName(ru):
    fn = getattr(ru, "getName", None)
    return fn() if callable(fn) else None


class _ExtStr(str):
    # ext 单次执行容错：本体是 str（字符串口径蜘蛛的 json.loads/切片/拼接
    # 语义全不变），同时把 ext 解析出的 dict 挂上 .get 与字符串键下标，
    # 让按 dict 口径写 init 的蜘蛛一趟跑通。旧做法是先按 str 调 init、
    # 撞 AttributeError 后把 ext 解析成 dict 整个重跑 init——第一趟已执行
    # 的副作用（登录/请求/写缓存）会重复，且判定只看异常文案，蜘蛛内部
    # 无关的字符串误用也会误触发重跑。这里改为执行前一次成型、只调一遍：
    # 解析不出 dict 就原样传 str，该失败的明确失败，不许半成功。
    def __new__(cls, raw, parsed):
        obj = str.__new__(cls, raw)
        obj._parsed = parsed
        return obj

    def get(self, key, default=None):
        if isinstance(self._parsed, dict):
            return self._parsed.get(key, default)
        return default

    def __getitem__(self, key):
        if isinstance(key, str) and isinstance(self._parsed, dict):
            return self._parsed[key]
        return str.__getitem__(self, key)

    def keys(self):
        if isinstance(self._parsed, dict):
            return self._parsed.keys()
        return []

    def items(self):
        if isinstance(self._parsed, dict):
            return self._parsed.items()
        return []

    def values(self):
        if isinstance(self._parsed, dict):
            return self._parsed.values()
        return []


def _wrap_extend(extend):
    if isinstance(extend, str) and extend.strip():
        try:
            parsed = json.loads(extend)
        except Exception:
            parsed = None
        if isinstance(parsed, dict):
            return _ExtStr(extend, parsed)
    return extend


def init(ru, extend):
    ru.init(_wrap_extend(extend))


def homeContent(ru, filter, gen=None):
    # 首页代次令牌（修 3）：Java 侧 SiteApi.cancelHomeLoads() 作废旧首页加载时把代次
    # +1 并推送到 _home_generation。调用方携带的代次若已过期，直接返回空 JSON，
    # 不再进脚本——防止被取消/排队中的旧任务在取消后仍打站点（僵尸加载止血）。
    # 只限首页两路；播放/详情/搜索/分类绝不受首页代次影响。
    if not _home_gen_ok(gen):
        return _EMPTY_HOME_JSON
    result = _fix_pics(ru, ru.homeContent(filter))
    formatJo = json.dumps(result, ensure_ascii=False)
    return formatJo


def homeVideoContent(ru, gen=None):
    if not _home_gen_ok(gen):
        return _EMPTY_HOME_JSON
    result = _fix_pics(ru, ru.homeVideoContent())
    formatJo = json.dumps(result, ensure_ascii=False)
    return formatJo


def categoryContent(ru, tid, pg, filter, extend):
    result = _fix_pics(ru, ru.categoryContent(tid, pg, filter, str2json(extend)))
    formatJo = json.dumps(result, ensure_ascii=False)
    return formatJo


def detailContent(ru, array):
    # TEMP-DIAG-REMOVE-AFTER
    import traceback as _tb
    import datetime as _dt
    _log_path = '/sdcard/xingchen_py_error.log'
    try:
        from com.chaquo.python import Python as _qp
        _qctx = _qp.getPlatform().getApplication().getApplicationContext()
        _qdir = str(_qctx.getExternalFilesDir(None))
        if _qdir and _qdir != 'None':
            _log_path = _qdir + '/xingchen_py_error.log'
    except Exception:
        pass
    def _dlog(_msg):
        try:
            with open(_log_path, 'a', encoding='utf-8') as _f:
                _f.write(_msg + '\n')
        except Exception:
            pass
    try:
        _r = _fix_pics(ru, ru.detailContent(str2json(array)))
        _jo = json.dumps(_r, ensure_ascii=False)
        _flag = 'EMPTY_RESULT' if len(_jo) < 50 else ''
        _dlog('[%s] detailContent OK ids=%s len=%d %s' % (_dt.datetime.now().isoformat(), array[:200], len(_jo), _flag))
        return _jo
    except Exception:
        _sp = ''
        try:
            _sp = ru.getName()
        except Exception:
            pass
        _dlog('[%s] detailContent EXCEPTION spider=%s ids=%s\n%s' % (_dt.datetime.now().isoformat(), _sp, array[:200], _tb.format_exc()))
        raise
    # TEMP-DIAG-REMOVE-AFTER-END


def searchContent(ru, key, quick, pg="1"):
    result = _fix_pics(ru, ru.searchContent(key, quick, pg))
    formatJo = json.dumps(result, ensure_ascii=False)
    return formatJo


def playerContent(ru, flag, id, vipFlags):
    result = ru.playerContent(flag, id, str2json(vipFlags))
    formatJo = json.dumps(result, ensure_ascii=False)
    return formatJo


def liveContent(ru, url):
    result = ru.liveContent(url)
    return result


def localProxy(ru, param):
    result = ru.localProxy(str2json(param))
    return result


def action(ru, action):
    result = ru.action(action)
    formatJo = json.dumps(result, ensure_ascii=False)
    return formatJo


def destroy(ru):
    fn = getattr(ru, "destroy", None)
    if callable(fn):
        fn()


def run():
    pass


if __name__ == '__main__':
    run()
