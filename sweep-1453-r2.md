# 红果修复批第二轮 验收扫查报告（sweep-1453-r2）

验收者：独立复核代理（与执行者分离）。范围：e597f5db 之后八笔提交（bd2f1945、cf572e89、69b23d49、4c229c25、85a53424、8f1db5ad、f83fb6d5、259e75dd）＋研究账 `~/workspace/hongguo/app-study.md`。只读核查，未改代码、未构建、未上真机。

## 结论

八笔落码整体成立：一到四项定性修复全部到位，第五项短片流改造没有误伤 MODE_SERIES 集流原链。**必修 1 条**（分类标签的数据形状对不上真回包，解析恒空），观察 5 条，其余点名无事。离线复跑：红果单测三套 ALL PASS（92 条断言）、本轮 9 个改动 Java 文件纯 parse 全过、5 份布局 XML 合法、剥离脚本干净树整跑退出码 0 且零残留。

---

## 必修

### B1. category_schema 在真回包里是「字符串化的 JSON」，解析器只认数组 → tags 恒空、标签筹永不显

- 证据一（真回包）：`~/workspace/hongguo/bookmall_tab_resp.json`（E3）第一条 video_data：`category_schema` 的值是 **str 型**（863 字符），内容是 JSON 字符串，二次解析后才是数组，元素形如 `{"category_id":5482,"name":"灾难","schema":"dragon8662://…"}`（实测内层 6 项：灾难/末世灾难/高能生存…）。
- 证据二（解析器）：`HgParser.asSeriesItem` 用 `arr(o, "category_schema")` 取数组，`arr()` 实现为 `e != null && e.isJsonArray() ? e.getAsJsonArray() : null`——元素是字符串时恒返回 null，tags 恒为空表。
- 证据三（消费面）：`HgFeedAdapter` 系列剧款的标签筹排遍历 `item.tags`（HgFeedAdapter.java:218 一带），真实 cell 数据下永远 `shown=0`、整排 GONE。短片流唯一已实证的数据源（cell）喂不出任何标签。
- 证据四（测试漏网）：`app/src/test/hongguo/HgOfflineTest.java` 对 tags/category **零断言**——执行者回报的「bookmall 回归」只验了抽出条数，没验 tags，所以三套全过仍漏。
- 连带：研究账 §2.5 把字段写作 `category_schema[]`（分类，含 name），未注明其字符串化形态，账面与真回包形状不符，照账施工必然漏。
- 修法（主会话裁）：`asSeriesItem` 对 category_schema 做两级容忍——元素是数组按现行抽；是字符串则 `JsonParser.parseString` 后再抽 name；同步订正研究账 §2.5 的形状注，并在 HgOfflineTest 补一条对存档回包的 tags 断言（期望含「灾难」）。

---

## 观察

### O1. enrichClip 的 onError 未走 main.post，与同类方法口径不一致（当前无害）
`HgFeedSession.enrichClip` 的 onSuccess 包了 `main.post`，onError 却直接在回调线程调 `changed.accept(-1)`（同文件 expandHead/resolveClip 两路都包）。当前唯一消费者（HongguoFeedActivity 落定富集）对 -1 直接短路、不碰 UI，所以今天无事；以后谁给 enrichClip 挂会碰 UI 的消费者就会踩线程。建议顺手补齐 main.post 保持全文件一致。

### O2. HgSeriesSheet.show 无 isFinishing/isDestroyed 守卫
`HgSeriesSheet.show` 直接 `dialog.show()`。触发路径都是用户手势同步调用（点剧条/左滑/点胶囊条），Activity 销毁中途插入的窗口极小；且既有的 HgEpisodeSheet 同样没守卫，属本包一贯写法。本轮不单列必修，留档：以后若有异步路径调它，先补守卫。

### O3. 研究账 §5.3 证据归属小误
账称 album 键名出自「classes11.dex 字符串池」，其中 5 个（is_multi_season/album_id_str/album_serial_count/album_data_list/album_video_list）确在 classes11.dex 命中；`same_album_short_series` 在 classes11 里 0 命中、实出 classes19/20.dex。键名本身真实存在（E1 成立），只是账的 dex 出处写错一个，建议订正账 §5.3 注脚。

### O4. U12 的归类：是「已定性缺陷」而非「未定」
预约回读 limit=50 写死是已知口径问题（账 §10.5 与 §14-U12 均已披露），放在「未定清单」里分类不准——它是已定性、待修。内容诚实无隐瞒，仅提示主会话在账上把它挪进缺陷账，免得日后被当「抠不到」放过。

### O5. 「我的」预约刷新与提交说明措辞有一处出入
4c229c25 提交说明写「回来以服务端为准重列」，但 HgMineView.refreshReservedFromServer 的实现是：服务端回空表时**不**冲掉本地镜像行（代码注释自明：别拿空结果冲掉已显行，旧列表页口径）。行为本身是审慎的（防服务端抽风清空用户预约单），与措辞不完全一致而已，记档备查。

### O6. 热评/作者声明两行是「有槽无数据」的死行（符合定案、待真机数据源）
全树查 `hotComment/hotKind/statement` 赋值点：只有 HgFeedItem 内部拷贝携带，**无任何数据源赋值**——两行在现数据面下永不显示。这与账 §3.4/§3.5＋U3/U4 的定案一致（有数据才显、不编），不是假数据问题；只是提醒：这两行的生死完全押在真机回包是否下发对应字段上，RTL/真机验收时要专门看一眼。

---

## 横屏（窗内转全屏）手势面专查（他亲点追加）

问题：官方横屏全屏下也能上下滑切集；咱们 HongguoFeedActivity 窗内转后，上下滑切集/切条是否还通、手势坐标系有没有跟着转、左滑进剧在横屏下是否误触。

### 结论：结构上通，坐标系跟着内容转，左滑不误触；未上真机（原实现提交 3e8decbb 自带「手势手感待真机」标记）

- **旋转的施加面**：`enterLandMode`（HongguoFeedActivity.java:857–886）把变换加在 `binding.getRoot()` 上——宽高互换＋`setRotation(-90f)`（:878）＋平移回中心。布局里 `hg_pager`（ViewPager2）是根的直接子视图（activity_hongguo_feed.xml:10–13），每个条目根（挂左滑 GestureDetector 处）都在这棵旋转树之内。
- **上下滑切集/切条接得通**：Android 的触摸分发对带变换的祖先按逆矩阵把 MotionEvent 换算到子视图的本地坐标再下发——分页器（竖向 ViewPager2）与手势检测器拿到的始终是「内容坐标」。所以内容转 90° 后，沿内容竖向的滑仍是竖滑分页，沿内容横向的滑仍走 fling 判定；用户把手机转到内容正立的握姿时，屏幕上的上下滑正对内容竖向，切集/切条与竖屏同理。
- **代码自证**：onSettle 里有 `if (landMode && position != landPos) exitLandMode();`（HongguoFeedActivity.java:378，注「滑离横屏页：窗内转自动退出」）——这条退出路径成立的前提就是 landMode 下分页事件照常触发；且全文件**没有**任何 `setUserInputEnabled(false)` 之类在横屏关分页输入的写法（grep 零命中）。
- **左滑进剧横屏误触面**：检测器（HgFeedAdapter 的 onFling）消费的是条目本地坐标，阈值 dx≤−80dp 且 |dx|≥1.5|dy| 在本地系里判——分页的竖滑在本地系里仍是竖向（|dy| 主导），与进剧判定正交，两种朝向下都不会被切集手势误触。需要注意的不是误触、是**方向随内容转**：横屏握姿下，映射到本地「左」的物理方向是观看者的右手方向，即横屏里是右滑进剧、左滑无动作——与内容朝向自洽，但和竖屏的肌肉记忆相反，手感是否合他意只能真机定。
- **界外项**：旋转方向取 -90° 的注释自带「方向手感待真机定」（HongguoFeedActivity.java:856 一带，3e8decbb 提交说明同标记）。本项的「通」是代码与框架机制层面的成立，物理手感（哪只手握、滑多远触发）属待真机清单，与账面原标记一致。

---

## 已查无事（点名）

1. **bd2f1945**：删的正是 HongguoSearchActivity.java:104 残留行；删后全树 grep `hgSearchBack` 零残留。
2. **cf572e89＋底栏互切**：底栏 `hg_bottom_bar` 高 56dp（activity_hongguo_feed.xml:205）与 hg_mine 的 `layout_marginBottom="56dp"`（:263）同值；pager 底距由代码 `setPagerBottomMargin(56)` 同步（setupShellFaces），三处一致。「我的」面是被收进底栏以上的区域布局（不是裁剪），格子滚动区末行不会被切。
3. **69b23d49＋抽屉重刷副作用**：三开关监听——启动直达/自动连播只写 HgStore；弹幕多一步 `adapter.bindDanmakuState(currentHolder())`，读的是 HgStore 当前真值重设透明度，幂等。openDrawer 重刷触发监听回写的都是同值，不存在拨反或连环触发。
4. **4c229c25 预约页签**：页签序 历史（0）/收藏（1）/点赞（2）/预约（3）与他亲定一致，布局新增 `hg_mine_tab_reserve` 与代码 findViewById 对位，点击接线走 tabViews 数组循环（长度已扩 4）；subscribe 写口/读口与 dd184c44 原文逐字对位（`POST /reading/bookapi/search/uncover_subscribe`＋BV、 `GET /reading/user/subscribe/list`＋BV，op_type 1/2、item_type 短剧 14 常量在 HgRequests:217–261）；迟到回包防护在：回调 post 后先判 `currentTab != TAB_RESERVE` 才落面。
5. **85a53424 模型层**：cell 新字段抽取全容忍式（tag_info 缺时回落 cover_tag_info_list[0]、vertical 只认 boolean 原始值）；album 兄弟部只在 depth==0 抽一层防自嵌；新字段默认值齐全（没带全空/false/-1）。
6. **8f1db5ad 会话层**：resolveClip/enrichClip 与 expandHead 并行存在、互不改写；`withDetail` 本轮被钉死恒产短片形态后，全部调用点只剩 resolveClip/applyClip/enrichClip 三处短片语境，集流页走 `episode` 工厂＋applyDetail，不经 withDetail——不存在把集页染成 clip 的路。
7. **259e75dd 集流原链未误伤**：MODE_SERIES 下 expandHead 展开/续播定位跳集/HgEpisodeSheet 选集/自动连播门（STATE_ENDED→isAutoNext→advanceNext）原样保留，只是被 mode 分工包住；saveProgress 的 MODE_FEED 早退在函数第一行，集流落盘条件（seriesId 非空、episodeIndex>0、pos>0）一字未动，断点仍记剧一级。短片流侧 STATE_ENDED 改循环当前条、saveProgress 关停、预热只 warm 相邻短片（剧头先 resolveClip、绝不 expandHead），与账 §2/§4 一致。
8. **HgSeriesSheet 触发守卫**：调用点 enterSeries 先判 `!item.siblings.isEmpty()` 才 show，show 内再判 rows 非空才建 Dialog——双层守住「只在回包真带 album_data_list 时才弹」；单内副行只拼集数＋角标、热度只用 rec_text，引言/演员/点赞数无字段不显（与账 §5.4/U5 一致）。
9. **新布局 Binding 对位**：view_hongguo_series_sheet（hg_series_close/hg_series_list）、item_hongguo_series_row（hg_row_cover/title/sub/heat/fav 五个）、item_hongguo_feed 本轮新增 id（hg_page_root/hg_series_style/hg_album_bar/hg_album_text/hg_series_title_row/hg_badge_chip/hg_series_title/hg_tags_row/hg_hot_row/hg_hot_comment/hg_hot_more/hg_statement/hg_fullscreen_watch）与代码引用逐个对上，无悬空字段；5 份布局 XML 合法性校验过。
10. **防崩同型（只扫本轮新代码）**：HgParser 新抽取无无守卫强转（getAsJsonObject 前有 isJsonObject、getAsBoolean 前有 isBoolean）；HgSeriesSheet 的 rows.get(position) 在适配器计数内；HgFeedItem.withSize 拷贝携带全部新字段（badge/recText/tags/multiSeason/albumId/albumSerialCount/siblings/hotComment/hotKind/statement/landscapeHint 一个不落）；本轮无 finalize、无裸 fromJson、无销毁后提交事务的新增面（HgMineView 的 post 回调切面即弃，见正文第 4 条）。
11. **研究账抽查 6 条**：§2.5/E3 字段逐字回存档回包验（vid/series_id/duration=5823 对 12 集/rec_text「4805万热度」/tag_info.text「爆剧」/vertical/play_cnt/score/video_desc/copyright 全部属实，唯 category_schema 形状见必修 B1）；§3.1/E5 右列次序与计数（星 7443/评论 705/心 1.8万）与实拍图逐项对上；§10.5/E8 预约两口与枚举回代码验（见第 4 条）；§12.1/E7 passport 宿主/发码/登录路径/XOR 0x05 回 passport-login-dig.md 验属实；§5.3/E1 album 键名回 dex 验（见观察 O3）；§6.5/E8 内核口径回代码验：hongguo 包内 MPV 仅一处注释提及（HongguoFeedActivity.java:49「不走 MPV」）、零代码引用，全页共用 ExoPlayerEngine（:92）属实。
12. **U1–U12 归类复核**：U2（cell vid 与集对位）在存档材料里确实定不了——E3 回包不含已验通的那集 vid（7694897951721655320，grep 零命中），推成未定属实；U3/U4 与代码现状（观察 O6）互证；U1/U5/U6/U7/U9/U10/U11 的未定都对应「样本/存档里无该面证据」，未发现哪条其实已能定却被推成未定（U12 的归类问题见观察 O4）。
13. **剥离脚本（第二道核）**：`git archive HEAD` 干净树整跑 `.github/scripts/strip-hongguo.sh` 退出码 0；剥后在 app 源码内 grep 本功能专属标识（tv.hongguo 包名、五 Activity 名、HongguoLoginProvider、loginHongguo、ic_nav_duanju、nav_duanju）零残留（唯一命中是脚本自身的锚点文本，非应用代码）；剥后 HomeActivity 底栏注册序＝vod/live/music/reading/miniapp/lab/setting 七格（短剧格已摘、余格次序与可见性与开格前一致），与正式版 8 格注册对照成立。
14. **磁力零触碰**：e597f5db..HEAD 的 diff 文件共 16 份，文件名 grep magnet/thunder/xl/torrent 零命中，本轮未碰磁力链（只作声明核对，与任务口径一致）。

## 待真机（照实，不在本轮结论内）
版本段官方值（现 /v1/）、短片流真回包形状与翻页、左滑阈值体感、横屏手势手感与旋转方向、抽屉滑入首帧、insets 实际留距、预约与 passport 真机回包、热评/声明/专辑关系三处数据面（U3/U4/U5）。
