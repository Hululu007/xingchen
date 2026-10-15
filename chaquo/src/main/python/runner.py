class Runner():
    def __init__(self, spider):
        self.spider = spider

    def getDependence(self):
        fn = getattr(self.spider, "getDependence", None)
        return fn() if callable(fn) else []

    def getName(self):
        fn = getattr(self.spider, "getName", None)
        return fn() if callable(fn) else None

    def init(self, extend=""):
        self.spider.init(extend)

    def homeContent(self, filter):
        return self.spider.homeContent(filter)

    def homeVideoContent(self):
        return self.spider.homeVideoContent()

    def categoryContent(self, tid, pg, filter, extend):
        return self.spider.categoryContent(tid, pg, filter, extend)

    def detailContent(self, ids):
        return self.spider.detailContent(ids)

    def searchContent(self, key, quick, pg="1"):
        return self.spider.searchContent(key, quick, pg)

    def playerContent(self, flag, id, vipFlags):
        return self.spider.playerContent(flag, id, vipFlags)

    def liveContent(self, url):
        return self.spider.liveContent(url)

    def localProxy(self, param):
        return self.spider.localProxy(param)

    def isVideoFormat(self, url):
        return self.spider.isVideoFormat(url)

    def manualVideoCheck(self):
        return self.spider.manualVideoCheck()

    def action(self, action):
        return self.spider.action(action)

    def destroy(self):
        fn = getattr(self.spider, "destroy", None)
        if callable(fn):
            fn()
