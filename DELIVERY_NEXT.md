# 下一版交付清单（待潇洒哥说"编"）

【2026-10-14 更新】1.3.1（versionCode 24）已交付：构建 Run 38529133575 成功，发布区已建 v1.3.1（只留这一版，含安装包与内部清单，验签=潇洒哥的证书指纹一致）。本版内容：海阔修复批（入库序列化治摸鱼线路/标题、页底纯白、网页垫白、刷新事件接线、长按标题多选删除、搜索条去糊、新鲜作用域补全预置函数治 getJsPlugin 未定义、图片解密数组转流治加密封面）+ 检查更新（关于弹窗按钮、默认自家发布直链、更新弹窗深色、构建自动发版）。下一版 1.3.2 候选：第三节 115 三修（3a79d849 已在本地）、海阔列表翻页拉不动（排查中）、推送 0 速（等当次日志）。新格式 JS 源（host 工具箱类）转接层排队中。

基准：现行交付版 1.2.8（versionCode 21）。下一版版本号按远端 build.gradle 实际值 +1。
推送前必做：先读远端 app/build.gradle 实际 versionName/versionCode 再递增；richtext.aar、smartrefresh-layout.aar 随代码一并推送（app/libs）。

## 一、海阔小程序整体重做（新模块 ui/hk，照 8.83 逐行转写，旧实现整体切换）
- 列表/详情页：HkArticleFragment + HkArticleAdapter + HkArticleListModel（60 栅格、57 种 col_type、bind 全量、clickItem/dealLazyRule/dealWithUrl/getChapters、长按菜单、fileSelect/input/select/confirm/copy 链接级处理）
- 宿主：HkListActivity（每级新开页、系统返回栈、续看位置、二维码/文件编辑回流）
- 引擎：HkEngineManager 进程单例（永不销毁、变量按规则隔离、preRule 每规则一次）；HkEngineBridge/HkHomeParser/HkCommonParser
- 搜索（整套新写）：HkSearchActivity + HkSearchFragment + HkSearchAdapter + HkSearchModel + HkSearchParser + HkSearchEngine（无搜索按钮回车触发、空词提示、精准/默认模式 smode、首末页指纹去重、空态/失败态伪条目、标题红高亮、多发动机状态条）；旧自创搜索历史不带入
- 播放交接：点视频走 VideoActivity.startHkPlay（选集、codeAndHeader/headers 随交接、x5Play:// 承载页、单集直达）
- 下载：download:// 对话框 + 快速下载入队（星辰 DownloadManager，支持通用文件）
- 行内 WebView：x5_webview_single / video 条目（HkWebViewHolder + HkWebkitHolder）
- 图片管线对齐 8.83：封面 override(320,480)+skipMemoryCache+DiskCacheStrategy.ALL；图片 client 读/写 15 秒、并发 48；缓存键回 GlideUrl 默认；磁盘缓存 250MB；占位按用户令保持透明
- hiker://images/ 映射：167 条全量（HkImageUrlMap）+ 167 个原版 drawable 拷入（含 3 个渐变矢量内联修复）
- 入口切换：小程序首页（规则列表）一行未动；点规则改开新模块独立页；旧三 Fragment 已不可达（死代码，下版验证后删除）

## 二、壳子三修（本地提交，未编）
- d20d6958 删/导入 py、js 插件后同步清缓存 + 刷新首页（修删源不刷新、重加源空白）
- 5a9bfddf 本地脚本文件已不存在时静默处理，不再弹「PY加载失败」错误码
- 01bc1505 下拉刷新失效：删 CustomRecyclerView 自创触摸握手，走安卓标准仲裁

## 三、115 原生播放三修（本地提交 3a79d849，未编）
- Proxy115 上游非 200/206 诚实回 502（不再把 403 错误体当媒体发给播放器）
- token 改纯 LRU、不再 90 秒过期（修播一会断）
- 云盘播放传真实文件名作标题

## 四、随 1.2.7 后本地既有提交（旧架构，多数被重做吸收，仍随行）
- 0563f667 第一次进入必失败修复 + 详情返回入栈；8035edd2 调用暂存重放；0acf55ed DNS 解析封顶 10 秒；05d2ab99 封面分栏（ContentAdapter 补接口）；cd9c9b30 详情播放走 clickItem 管线；77586fab/bbefc718/31ad7861 加载指示统一为顶部细线；c766a818 long_text 去白色底板

## 五、验证状态
- javac 解析级全量自检干净（新模块整包 + HkPageActivity，含接收者符号挖掘复扫）
- 本地 JVM 回归 25 项全过（新搜索链真编译真跑 + 摸鱼日报真规则解析）
- 未做：真机验证（须编后装机）；迅雷 ed2k 复测、crack114 v8 复测仍待真机

## 五、1.2.9 待编批次（1.2.8 真机反馈修复，2026-10-14）
- hiker:// 内部地址全灭修复：HkHttpParser.get/post 改委托 HkCodeUtil（补回 8.83 的地址分发层：hiker://empty/page/home、hiker://files/、file:// 本地读文件，其余才走网络）；HkCodeUtil 清除标记改用原样版 + 补冲突串解码 + 文件不存在改 404；HkHikerRuleUtil 异步版补齐（格式有误 404、获取数据失败 500）。本地已用摸鱼日报、XO 两条真规则验过取页链。
- 状态栏透出星辰壁纸修复：全局壁纸机（ThemeManager）在每次页面恢复时把窗口底换成用户壁纸，海阔浅色页（HkListActivity/HkSearchActivity）被误伤。修：这两页跳过壁纸、窗口底与状态栏保持浅色不透明；两页 onCreate 同步设白色状态栏防首帧闪。
- 顶部小程序切换 tab 恢复：重做时随旧内容页一起丢了，现自旧 HkContentFragment 原样迁回 HkListActivity——标题栏下方横向已启用小程序 tab（金色选中）+ 最右「全部 ▾」两列卡片弹窗；仅规则首页级显示，子列表级不挂；点击切换 = 新页打开目标规则并关本页。

## 六、磁力/ed2k/推送四修（本地提交 3c7a80d6，2026-10-14，随下一版编）
真机证据：推送播仙逆剧场版，磁力与 ed2k 都是 0%、0KB/s 黑屏；标题是整条 ed2k 原串（带大小段）。三路定案（报告：workspace/thunder_engine_diagnosis.md、workspace/push_ed2k_diagnosis.md）：
- 引擎层无回归：thunder/ 模块与 1.2.4（家网实证 4.9MB/s）逐字节一致；初始化/建任务/启动调用都在。双零的环境嫌疑已排除（2026-10-14 14:07 他明确"我一直用的手机网络"，不是公司 WiFi；助手先前"公司网掐 P2P"的判断作废）。
- ed2k 就绪信号是真 bug：旧代码读 saveFile.length()，而保存文件建任务瞬间被预分配成全长 → 就绪门第一次轮询即假放行、零真实字节。修：XLTaskHelper 新增 getNativeTaskInfo（绕开 getTaskInfo 的 status 合成、直读原生 mDownloadSize/mFileSize/mTaskStatus），addThunderTask 改用之。
- 磁力就绪门被零星字节骗开：旧"两次见涨放行/3 秒不涨放行"已删。新放行：下完；或 ≥512KB 且近 2 秒在涨；或 ≥4MB。上限 60→25 秒（卡进 SiteViewModel 播放解析 30 秒超时内，到时 ≥512KB 交地址试播、不足抛"连接超时"真错误）。
- 推送标题：SiteApi.detailContent 的 PUSH 分支 setName 改走 pushTitle（ed2k 取文件名段、磁力取 dn 参数，取不到回退原串）；id/playUrl 不动。
- 吞错：SiteViewModel.execute 失败回调补 SpiderDebug 留痕（viewmodel 标签）；PLAYER 的 TimeoutException 改回 Result.error("连接超时")，不再吞成通用"播放地址加载失败"。
- 待真机验：①同一条磁力在新版下是否出数据、waiting data 曲线如何（日志定案）；②日志 thunder 标签 data ready 的 size 应为真实字节、ed2k 不再出现"瞬间全长放行"；③推送标题显示文件名。
- 已知次生隐患（未在本批动）：多集磁力预加载下一集会调 Source.stop() 删掉当前在播的迅雷任务（NextEpisodePreloader，10-12 自加），单集不触发，日后单独处理。
- 追加（本地提交，接四修之后）：**预加载杀任务**——14:0x 他补实证"开播约 80KB/s 后流量归零直到失败、断后不恢复"，定案为 setPlayer 尾部 preloadNextEpisode 的确定性杀伤（单集钳位回当前集 + 推送集无 selected 标记 → 恒触发预加载自己 → 后台 playerContent 首行 Source.stop() 删在播任务+释放引擎）。修：preloadNextEpisode 加同址跳过与磁力类（magnet/ed2k/thunder）跳过两道守卫（普通源预加载与消费端 consumePreparsed 不动）；Thunder.stop() 补 synchronized。注意：子代理曾误报 consumeIfMatch 零调用，实测有调用（VideoActivity:1721），预加载对普通源是有效设计、不可整摘。

## 七、1.3.0 已交付（2026-10-14 14:28，Run 38521779526，1.3.0/23）
- 推送 15 文件全校验一致（含 thunder/ 的 XLTaskHelper，推送脚本已扩围 app/+thunder/）。
- 内容 = 第五节五项 + 第六节磁力五修，全数编入。待真机验，重点：推送磁力开播后流量持续不再归零；摸鱼帖双线路；「一个APP」封面恢复；分类钮白框消失。
- 验完主链后删旧三碎片死代码（他 11:00 拍板"验完再删"）。
