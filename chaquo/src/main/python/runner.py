class Runner():
    def __init__(self, spider):
        self.spider = spider

    def getDependence(self):
        fn = getattr(self.spider, "getDependence", None)
        return fn() if callable(fn) else []

    def getName(self):
        fn = getattr(self.spider, "getName", None)
        return fn() if callable(fn) else ""

    def init(self, extend=""):
        try:
            self.spider.init(extend)
        except AttributeError as e:
            if "'str' object has no attribute" not in str(e):
                raise
            parsed = {}
            if isinstance(extend, str) and extend.strip():
                try:
                    import json
                    parsed = json.loads(extend)
                except Exception:
                    parsed = {}
            if not isinstance(parsed, dict):
                parsed = {}
            self.spider.init(parsed)

    def homeContent(self, filter):
        return self.spider.homeContent(filter)

    def homeVideoContent(self):
        fn = getattr(self.spider, "homeVideoContent", None)
        return fn() if callable(fn) else {"list": []}

    def categoryContent(self, tid, pg, filter, extend):
        return self.spider.categoryContent(tid, pg, filter, extend)

    def detailContent(self, ids):
        return self.spider.detailContent(ids)

    def searchContent(self, key, quick, pg="1"):
        return self.spider.searchContent(key, quick, pg)

    def playerContent(self, flag, id, vipFlags):
        return self.spider.playerContent(flag, id, vipFlags)

    def liveContent(self, url):
        fn = getattr(self.spider, "liveContent", None)
        return fn(url) if callable(fn) else ""

    def localProxy(self, param):
        fn = getattr(self.spider, "localProxy", None)
        return fn(param) if callable(fn) else None

    def isVideoFormat(self, url):
        fn = getattr(self.spider, "isVideoFormat", None)
        return fn(url) if callable(fn) else False

    def manualVideoCheck(self):
        fn = getattr(self.spider, "manualVideoCheck", None)
        return fn() if callable(fn) else False

    def action(self, action):
        fn = getattr(self.spider, "action", None)
        return fn(action) if callable(fn) else {}

    def destroy(self):
        fn = getattr(self.spider, "destroy", None)
        if callable(fn):
            fn()
