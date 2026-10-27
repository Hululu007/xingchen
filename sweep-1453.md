# 1.4.53 交付前全面大扫查报告（分支 my-1436，基线 e8b0aad7 → HEAD e597f5db）

扫查时间：2026-10-27 晚。方式：本批全部 diff 逐文件读完＋接缝互查＋防崩同型扫（本批新代码）＋剥离脚本干净副本整跑＋JDK21 纯 parse＋红果离线测试重跑。未改任何代码、未推送、未构建。

**范围说明**：任务清单列 7 笔，实际区间 e8b0aad7..HEAD 共 **8 笔**——多出一笔 dd184c44（红果预约接真口子，位于 dcfa2bea 之前），同属本批，一并扫了。

状态声明：本报告是离线扫查结论。insets 实际留距、抽屉滑入首帧、底栏显形、预约与 passport 的真机回包都属「待真机」，静态判不了的已在观察项注明。

---

## 一、必修问题（3 个，先别编，修法已写清）

### 必修 1搜索页残留已删布局 id 的 Binding 消费点
- 证据：`app/src/main/java/com/fongmi/android/tv/hongguo/ui/HongguoSearchActivity.java:104`
`binding.hgSearchBack.setOnClickListener(v -> finish());`（initEvent 内）
而 e597f5db 已把布局 `activity_hongguo_search.xml` 里的 `@+id/hg_search_back` ImageView 删除（当前布局只剩 hg_search_top/input/go/state/list）。
- 为什么必挂：本页用 ViewBinding（ActivityHongguoSearchBinding 按布局生成字段），布局里没了这个 id，生成的 Binding 类就没有 hgSearchBack 字段 → 真机构建 cannot find symbol。离线纯 parse 查不出（不生成 Binding 类），执行代理三套离线测试也覆盖不到。
- 定性：正是 AGENTS.md 记过的「删布局 id 必须 grep 全部 Binding 消费点」同型再犯。e597f5db 提交说明称「无代码引用该 id」是误判——它只 grep 了 drawable 名 ic_hg_back，没 grep Binding 字段名 hgSearchBack。
- 修法：删掉 HongguoSearchActivity.initEvent() 里这一行（返回走系统返回，与摘箭头同口径）。一行删除，不动其他。

### 必修 2「我的」面整面盖住红果自家底栏，「首页」格点不到
- 证据：`app/src/main/res/layout/activity_hongguo_feed.xml` 子视图顺序——hg_bottom_bar 在第 203 行，hg_mine 的 include 在第 259 行（FrameLayout 里后者压在上层），include 为 match_parent×match_parent，HgMineView 根背景 #FFFFFF 不透明。`HongguoFeedActivity.showMineFace()` 只把 mine 面置 VISIBLE，不给底栏留位。
- 后果：切到「我的」面后底栏被整面盖住，点不到「首页」格，只能靠系统返回（onBackInvoked 有 mineShowing 兜底，能回，但不是定案的两格互切形态）。官方「我的」页底栏是常驻可见的（他发的谷歌版图 113479 底栏在位），咱们这面把底栏藏没了，形态对不上他亲定的「底栏就首页/我的两格、互切」。
- 修法（二选一）：①把 hg_mine 的 include 挪到 hg_bottom_bar 之前（层叠在底栏之下）；或②给 include 加 `android:layout_marginBottom="56dp"`（与底栏高、pager 底距同值）。顺带确认：mine 面的 insets 顶距注入走的是根 view padding，不受此改动影响。

### 必修 3抽屉弹幕开关会显示旧值、照着拨会拨反
- 证据：`HongguoFeedActivity.setupShellFaces()`（约 904–919 行）只在 initView 时给三个 Switch 各 setChecked 一次；`openDrawer()` 不重读 HgStore。而弹幕有第二个写入口：剧内弹幕键 `onDanmakuToggle()`（757 行）直接 `HgStore.setDanmakuOn(!...)`。
- 后果：用户在剧内关掉弹幕后再打开抽屉，抽屉的弹幕开关仍显示进页时的「开」，与真实状态相反；照显示拨动等于把状态拨反。属任务口径明列的逻辑错类（开关显示与实际状态对不上）。
- 另两个开关（启动直达、自动连播）只有抽屉一个写入口，不受影响。
- 修法：`openDrawer()` 里对三个开关按 HgStore 当前值各 setChecked 一次重刷。监听回写的是同值，对 HgStore 无副作用。

---

## 二、观察项（不拦编，逐项点名）

1. **预约页签未上「我的」面**：dd184c44 已把预约写链/回读落码（HgApiClient.subscribeSeries/fetchMyReservations、HgRemoteInteractions 回读对齐、HgStore 镜像），刷剧页预约片与（已删的）剧场/列表页都接过；但 457 重做的 HgMineView 页签排只有历史/收藏/点赞，没有他定的「预约放点赞后面」。旧「我的预约」列表页随 HgListActivity 删除后，fetchMyReservations 只剩 toggleReserve 内部回读在用，用户没有地方看自己的预约单。预约链真机未验，按「链没通不摆假占位」先不摆也说得通——**请主会话裁**：本批是否补预约页签（数据走 HgStore.reservedIds 镜像＋fetchMyReservations 刷新，旧 HgListActivity 的 KIND_RESERVED 逻辑在 git 历史可捡回）。
2. **预约回读只取第一页**：HgRequests.subscribeListParams 写死 limit=50/offset=0。预约超过 50 部时，刚预约的剧不在回读首页里，HgRemoteInteractions.toggleReserve 的回读对齐会把 serverState 判 false、翻掉乐观值（服务端实际已预约成功，页面显示未预约）。低概率、确定存在。
3. **取模型兜底等待叠加**：HgPlayResolver.fetchModel 改后为 multi 一轮＋单集口最多 3 轮串行阻塞（每轮等 latch 超时），全败时起播等待是四轮超时之和，体感会慢。这是换指纹避限速的设计取舍，仅记录。
4. **短信登录弹窗的 callback 是普通字段**：HongguoSmsLoginDialog 若因配置变更被 FragmentManager 重建，callback 字段丢失，登录成功后 cookie 无人接（不落托管）。当前入口（刷剧页 configChanges 全套自管、首页竖屏）触发概率低，且与既有 PanLoginDialog 同模式，记录不拦。
5. **insets 监听不消费返回值**：刷剧页与搜索页的 OnApplyWindowInsetsListener 都 `return insets` 不消费。静态查两页内没有第二个 insets 消费方，不会双重留距；实际留距效果待真机一眼。
6. **ic_hg_back.xml 已成无用资源**：搜索页箭头摘除后全树零引用（仅剩文件本体与剥离脚本清单登记）。正式版留着无害（朋友版剥离时会删），可顺手删、不删不影响构建。
7. **抽屉滑入首帧**：openDrawer 先 setVisibility(VISIBLE)、再 post 里设 translationX=-width 后动画滑入，post 一般在绘制前执行；有没有一帧闪烁静态判不了，待真机。
8. **Manifest 删登记后留连续空行**（457 的 mobile Manifest diff 可见），纯观感，无功能影响。
9. **passportError 判定偏保守**：回包有 message、无 data、message 非 "success" 时按失败处理——宁可报失败不假成功，与提交口径一致，记录。

---

## 三、已查无事清单（点名结果）

1. **本批 8 笔 diff 逐文件读完**：dd184c44（20 文件）、dcfa2bea（6）、d168c36a（1）、46bd6c0d（4）、51122b58（5）、ac8021dd（2）、457a8c5f（17）、e597f5db（1），含全部新增文件全文（HgPassportClient、HongguoSmsLoginDialog、HgMineView、三份新布局、两枚新图标）。
2. **剧场三页删除零残留**：类名 HgTheaterActivity/HgLibraryActivity/HgListActivity 在 app/src 零命中（仅剥离脚本的残留校验名单里有，作为删除断言）；mobile Manifest 只剩 HongguoFeedActivity、HongguoSearchActivity 两登记，leanback Manifest 无红果登记；三份已删布局零引用；三页的 Binding 类（ActivityHongguoLibrary/List/TheaterBinding）零引用；保留的 item_hongguo_theater.xml 有活消费（HgMineView 的海报格子）；三页标题都是代码内字面量、无专属字符串资源残留。
3. **XcBottomBar 颜色源只剩 selector_nav 一处**：normalColor/selectedColor 同从 R.color.selector_nav 取，state 取值对得上（-state_checked→#171B23、+state_checked→#F0A400，与 selector 两条目逐字一致）；类内余下唯一的 resolveAttribute 是按压水波纹背景（非取色）；着色插值链只在两色之间；TypedValue import 仍在用（第 119/132 行），不悬空。
4. **HomeActivity 冷启直达块**：位于 initView 末尾，四条件齐才触发（savedInstanceState==null＋isHongguoTabEnabled＋Intent.ACTION_MAIN＋HgStore.isLaunchDirect）；initView 只由 onCreate 走、onNewIntent 不经它，不会二次触发；开关默认关（isLaunchDirect 默认 false）时整块零影响；首页不 finish、刷剧页压其上，返回落首页，符合他的定案。
5. **刷剧页一壳两面与窗内转的底栏收放**：showMineFace 暂停播放并记 resumeAfterMine、showFeedFace 按记恢复；onBackInvoked 顺序为抽屉→我的面→横屏→原逻辑；enterLandMode 藏底栏＋pager 底距置 0、exitLandMode 恢复底栏 VISIBLE＋底距 56dp，与底栏布局高 56dp 一致；onDestroy 调 HgMineView.cancelPending() 停登录轮询。（底栏被「我的」面盖住的問題在必修 2。）
6. **HgApiClient 15 条路径版本段无遗漏**：全类 20 条路径逐条枚举——bookapi 家族 14 条（search/tab、search/cue、search/suggest、search/cell/change、new_category/front、new_category/landing、new_category/cell、plan、bookmall/tab、bookmall/cell/change、bookshelf/video/list、bookshelf/video/info、bookshelf/video/update、search/uncover_subscribe）＋user/subscribe/list，共 15 条全部拼 BV(/v1/)；novel/player 家族 5 条自带 /v1/；全树再无第二处拼 bookapi 路径（其余命中全是注释）。
7. **剥离脚本第二道核通过**：用 git archive 干净副本整跑 strip-hongguo.sh，退出码 0；剥后 grep HongguoSmsLoginDialog/isLaunchDirect/HgStore/HongguoFeedActivity 零残留；isHongguoTabEnabled 被正确改回 false；脚本锚点逐项与当前源码对位（直达块 cut_exact 原文、LoginManager 两处 dcfa2bea 后的新 javadoc、Manifest 登记名单只剩两页、RES 清单含 view_hongguo_mine/view_hongguo_drawer 与 ic_hg_home/ic_hg_mine、HongguoSmsLoginDialog 整删登记）。
8. **磁力起播链：本批未碰**。改动文件全清单（42 个）里零个磁力/迅雷相关文件，按口径只作声明、无实测。
9. **1.4.52 功能未被误伤**：底栏注册 8 格齐全（vod 格改用新字符串 nav_home，nav_vod 本体保留、menu_nav.xml 旧引用不悬空）；syncOptionalTabs 的实验室/小程序二选一逻辑未动；本批 diff 零个海阔（hk）文件、零 proguard-rules.pro 改动——海阔函数八个与解析面、字幕插件 IAiAsrBridge 的 keep 规则都没被本批触碰。
10. **防崩同型扫（本批新代码）**：
- 无守卫强转：本批 4 处 MarginLayoutParams 强转（刷剧页 hgTopBar/hgDrawer/hgPager、搜索页 hgSearchTop），父容器全是 FrameLayout/LinearLayout，LayoutParams 类型成立；其余无对象强转命中。
- 同 id 跨布局变体：红果无 layout-land/sw 变体目录，不适用；本批唯一的布局 id 删除事故就是必修 1。
- 泛型反序列化：新代码无 Gson fromJson，全部走 JsonParser 手工解析＋空守卫；HgParser 三个未自带 try 的入口（parseSubscribeList/parseSingleVideoModel/parseCellPage）的生产调用点全在 HgApiClient.enqueue 的 onBody，处于 onResponse 的 try/catch 包裹内，畸形回包不会崩、只会走 onFail。
- 服务未就绪直调：HgStore 走 catvod Prefers（App 级已初始化）；HgPassportClient 的 OkHttp.newCall 有 null 守卫。
- 销毁后弹窗/回调：短信弹窗网络回调全走 App.post＋stopped 守卫；HgMineView 的退出登录 AlertDialog 是点击直达、无异步回落；HgMineView 登录轮询在 Activity onDestroy 有 cancelPending 收口。
- 桥对象 finalize 释放：本批新代码零 finalize。
11. **开关读写成对**：启动直达（抽屉写 HgStore、HomeActivity 读）；自动连播（抽屉写、Player STATE_ENDED 门读，默认开与此前行为一致）；清红果缓存（HgModelCache.clear() 实存，抽屉行点按真清）；弹幕（抽屉与剧内键同读同写 HgStore，生产消费成对——显示过期问题在必修 3）。
12. **登录链删常量收尾**：HongguoLoginProvider.LOGIN_URL/COOKIE_DOMAIN 删除后全树零消费；LoginManager 的 loginHongguoDelivered/isHongguoLoggedIn/logoutHongguo 三桥都在，HgMineView、HongguoFeedActivity 的调用点逐一对得上；startSeries 四参签名、App.post(Runnable,long)、R.style.HkDialogFade（main 源集既有同款用法）都核过在位。
13. **离线复核结果**：改动范围内现存 22 个 Java 文件 JDK21 纯 parse 全过；app/src/test/hongguo/run.sh 三套离线测试本次重跑 ALL PASS。
