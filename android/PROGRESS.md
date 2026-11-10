# Android 进度

更新：2026-11-11 05:45（Asia/Shanghai）· 断点续做：先看「当前进行中」

## 当前进行中
- 对比截图（shots/compare/，本地）：01 首页、02 频道面板、03 搜索、04 详情、05 播放器、06 选集面板、07 弹幕 已出（截图改用 `adb emu screenrecord screenshot`：视频 SurfaceView 在 TCG 下会让 `screencap` 返回旧帧）
- 仅非成人、非采集站截图进仓库 docs/screenshots/
- 注意：模拟器为 TCG 软渲染，debug 包首页常 ANR 被杀 → 截图用 release 包（同签名覆盖安装，数据保留）

## 对齐清单（iOS → Android）
| 模块 | 状态 |
|---|---|
| 红果：注册/榜单/详情/取流/CENC 解密/播放 | ✅ 模拟器真数据 E2E |
| 红果软拒绝 | ✅ 根因已定位，one-shot 设备修复（见下） |
| 详情：题材标签/演员/收藏数/系列（关联季） | ✅ 34a78e6（JVM 真数据：4 标签、14 季） |
| 播放器：手势/倍速/画质/技术标签/S 季标 | ✅ |
| HLS 去广告（逐条移植） | ✅ 10 测 |
| 苹果 CMS（maccms v10） | ✅ bcaa66b，模拟器：分类/详情/HLS 播放，去广告 14 段 51 s |
| idj1 JS 插件运行时（QuickJS） | ✅ 682f7f0/e057cd7/524b52d：宿主 API 全量（http 白名单+逐跳校验、html、crypto、codec、m3u8、storage、settings、log），视频插件首页/分类/搜索/详情/播放；「我的」安装/开关/删除 |
| 漫画插件（JSComicProvider） | ✅ 0fe4331：home/category/search/detail/pages；idjimg:// 图片引用（逐插件媒体域白名单、逐跳校验、头过滤、30 MB 上限、AES / 条带重排变换）；内置漫画插件均为成人 → 仅成人开关下显示，未上机实测 |
| 选集面板 | ✅ 更新至 N 集、季 chips（新→旧，点选切季）、每 30 集区间 chips、6 列、当前集描边（compare-06） |
| 解码能力回退 | ✅ 按 MediaCodecList 选设备能解的最高档（模拟器软解 HEVC 1080p 报 NO_EXCEEDS_CAPABILITIES → 降档）；iOS 恒有 HEVC 硬解无此问题 |
| 首页「频道」面板（⌄） | ✅ e4ae037：标题+完成、按页分组（红果 全部榜单/真人剧/漫剧/剧星榜；黄果 视频站/AI 站/App 源；AI剧；采集站/插件），点选切页+子频道 |
| 弹幕（番茄评论接口签名、合并、轨道布局） | ✅ 5b5b587：JVM 实网 万妖图录传 第1集 126 条；模拟器目检：顶部 4 轨、×N 合并计数（compare-07） |
| 搜索页（占位、历史/发现横滑 chips） | ✅ d7ba8b7 |
| 频道（TG 公开频道网页预览 t.me/s，无需账号） | ✅ 6f8befc：解析器（iOS 同 fixture 测试）、实网 telegram 20 帖/13 视频、订阅/合并流/翻页/视频播放；不内置任何频道；TDLib 不移植 |
| 黄果（AI 站镜像+取流 API、huangguo.video、旧 App AES 接口、封面 AES/XOR 解码） | ✅ 4fe7553：JVM 实网 视频站 37 部/取流 HLS、AI 站 20 部/21 集/master #EXTM3U（x-cdn-auth 头）、封面解密为 JPEG 1086×1448、App 源 6 个 tab/19 部。成人片源：仅在成人开关打开后显示；未成年 + 非自愿题材过滤（视频站 50→37）；不截图、不进仓库 |
| 漫画（包子漫画、MangaDex；首页/详情/阅读器 上下滚动+左右翻页、续读、收藏） | ✅ 468edfd：iOS fixture 测试；实网 包子 36 部/一人之下 823 章/17 页，MangaDex 30 部/96 章/37 页；离线下载未移植；插件漫画待做 |
| 搜索多源（红果/黄果/采集站/插件 源切换） | ✅ abad6c7 |
| 插件订阅 / 签名校验 / CF 验证 / 执行超时中断 | ❌（QuickJS 包装库无中断钩子：超时即弃用实例） |

## 提交（main）
- 59f5f9d 软拒绝根因 + one-shot 设备 + 模拟器出口开关 + UI 修正
- 34a78e6 详情增强（web _ROUTER_DATA、系列落地、关联季、SeasonParser）、播放器季标、Hero 简介自适应
- bcaa66b 苹果 CMS 适配 + ContentFilter + NetGuard + HLS 本地清单去广告
- 682f7f0 idj1 插件宿主 + 引擎无关运行时 + 视频映射 + 测试
- e057cd7 QuickJS 引擎、PluginStore、plugin: 路由、播放器请求头、调试自测
- 524b52d 「我的」插件管理；简介实体清理
- 7d05d02 PROGRESS 中文化
- d7ba8b7 搜索页对齐 iOS
- 5b5b587 弹幕（签名移植 + 合并 + 轨道 + 叠层）
- 6f8befc Telegram 频道页（模拟器：订阅 telegram → 20 帖、视频帖点开即在播放器播放）
- 3c19a61 频道：链接可点、预览卡样式
- 4fe7553 黄果
- 468edfd 漫画
- abad6c7 搜索多源 + PROGRESS
- 0fe4331 漫画插件 + idjimg:// 图片抓取
- e4ae037 首页「频道」面板
- 6a5fd77 PROGRESS
- d7b67c8 选集面板（季/区间/更新至）
- e8a82c9 解码能力回退
- 52a6ad4 低版本（API<31）封面模糊背景
- 96c0088 弹幕去除 U+FFFC/BOM（安卓显示为 OBJ 方框）
- 412e4ef 详情头图渐隐、简介字号对齐 iOS

## APK（/workspace/iduanju-android/dist，SHA256 见 dist/SHA256SUMS）
- 见文末「APK」节（每次构建后更新）

## 红果软拒绝：根因与修复（2026-11-11 实测，纯播放端点 video_model level 3）
探针：`core/src/test/.../SoftRejectProbeTest.kt`（`-Plive=1 -Pprobe=1|2|3`，设备只在运行时传入，不落盘）

| 臂 | 序列（P=有 video_model，F=200+0 字节） |
|---|---|
| 对照：已知可用设备（仅运行时 -PhgDevice） | PPPPPP；12 次穿插 PPPPPPPPPPPP |
| 本机 mint 15 位 iid=did+4096 ×3 | FFFFFF ×3 |
| mint + 随机 cdid/openudid | FFFFFF |
| 随机 19 位未注册 | FFFFFF |
| mint + app_log 启动事件 + log_settings（均 200 success） | FFFFFF |
| mint 预热 16 次 × 3 s | FFFFFFFFFFFFFFFF |
| **device_register（与播放同档 7.3.9.32 / Xiaomi 23127PN0CC）** ×2 | **PFFFFF**、**PFFFFF** |
| 注册 + app_log + log_settings | PFFFFF（激活无帮助） |
| 注册后等 15/45/90 s 再请求 | P → F F F（不恢复） |
| 同设备 4 个不同剧集 | PFFF（按设备计，不按 vid） |
| 注册后先等 20 s 再请求 | PFF |
| 注册后首请求用派生 cdid / level 4 | P / P |
| **每次请求换一台新注册设备** 间隔 1.5 s ×6 | PPPPFF |
| **每次请求换一台新注册设备** 间隔 6 s ×12 | **PPPPPPFPPPPP（11/12）** |

结论：
1. 本机 mint 的设备从未在上游注册，纯播放端点从第一次起就软拒绝；激活（app_log/log_settings）、预热、换 cdid 都无效——与网关 10-20 记录一致。
2. 用与播放请求同一档案（7.3.9.32 / 小米）在 log.snssdk.com 注册得到的新设备 **恰好放行一次**，之后永久软拒绝（按设备计数，与 vid、间隔、激活无关）。旧档（7.3.3.32 / sdk_gphone64）注册的设备则直接拒绝。
3. 修复（不写死任何 ID）：`HGOneShotDevice` —— 无覆盖时每次取流现注册一台同档设备，只用一次；每档位最多 2 次（实测 11/12 成功）。路由顺序：one-shot → 本地 mint → batch → single；设置了覆盖则直接走覆盖。
4. 证据：`HongguoLiveTest.detailAndResolve` 与 `CENCLiveTest` **无覆盖** PASS（route=oneshot，CENC 8,937,645 B 解密，首盒 ftyp）；模拟器内 E2E（`--es clear 1`）RESULT OK。
5. 风险：依赖上游 device_register 不限流；短间隔（1.5 s）出现 F，下一集预取已与当前集错开。

## 模拟器联网（已修复）
- 症状：DNS 通（盒子 fake-ip），直连 443 得到非 TLS 回包 → `SSLException: Unable to parse TLS packet header`。
- 方法：盒子出口是本机 HTTP CONNECT 代理；模拟器里 `settings put global http_proxy 10.0.2.2:<端口>`（10.0.2.2 = 宿主回环）。
- 播放器会话按 iOS 绕过代理（NO_PROXY），为模拟器加开发开关 `dev.playerSystemProxy`（E2E：`--es proxy 1`），默认关闭。
- AVD idj30（API 30 x86_64，TCG，2 GB）。SystemUI 常 ANR，`adb root` 后杀掉 systemui 恢复。

## 测试（2026-11-11 05:20）
- 汇总：core 14 个测试类 41 项，离线全过（13 项为在线用例，默认跳过）；05:20 复跑在线 HongguoLiveTest 3（无覆盖，one-shot）、HuangguoTest 5、MangaTest 3、TGParserTest 3：全过
- 离线：HuangguoTest 4、MangaTest 2（iOS fixture）、TGParserTest 2（iOS fixture）、HGDanmakuTest、HGSignGolden 2、HLSAdFilter 10、Medusa 1、MacCMSTest 2、PluginHostTest 4（manifest/域名策略/crypto/html/m3u8/unpack/storage/重定向拦截/限流；3 个内置 iOS 插件均可解析）：全部 PASS
- 在线（-Plive / -PcmsApi，地址只在运行时传入）：HongguoLiveTest 3、HGDanmaku live（126 条）、TG live（20 帖）、黄果 live（三子源）、漫画 live（包子/MangaDex）、CENCLiveTest（route=oneshot）、MacCMSLiveTest、PluginHostLiveTest：PASS
- 模拟器（AVD idj30，API 30 x86_64，TCG，经宿主代理）：
  - 红果 E2E RESULT OK（注册→榜单→详情 202 集→1080p bytevc1 加密→解密 8,937,645 B ftyp），UI 播放 HEVC
  - CMS：源栏「采集A」、详情、HLS 播放
  - 插件：`--es pluginSelfTest 1` → SELFTEST OK（真 QuickJS：async/await、html、aes、storage、域名拦截 E_PERMISSION、宿主错误码 E_NOT_FOUND）
  - 频道（TG）：订阅 telegram → 20 帖，视频帖播放；黄果三子源（不截图）；漫画 包子 首页/详情/阅读；弹幕叠层真数据；频道面板
  - 插件实网：本地桥接插件（不进仓库）→ 列表 20 / 分类 20 / 详情 8 集 / play→HLS 去广告 14 段 → UI 播放 0:27

## 真机检查清单
- 播放流畅度、PiP、HEVC 硬解、CENC 解密速度
- one-shot 设备在移动网络 / Wi-Fi 下的成功率（模拟器 11/12）
- CMS HLS 播放与去广告；插件 QuickJS 在 arm64 上加载与执行耗时
- 深色/浅色主题、刘海屏、横屏
- 弹幕叠层帧率（高密度时）；TG 视频直链（带时效 token）播放

## 待做
- 频道管理/屏蔽规则/下载、漫画离线下载
- 黄果在 iOS 分属「黄果」「AI剧」两页，Android 合并为「黄果」一页的 chips（视频站/AI·/App·）
- 插件订阅/推荐插件、执行超时中断

## APK
构建于 e8a82c9（2026-11-11 05:20；含频道面板、选集面板、解码回退、漫画插件；release 用 Android Debug 证书签名；未混淆）
```
iDuanJu-1.5.2-android-debug.apk 28633095
iDuanJu-1.5.2-android-release.apk 20579127
218945c4aeb03a1fea58d27295c760f8189740bd26c7a4927e92ddf968f1658b  iDuanJu-1.5.2-android-debug.apk
e0da4899260e1c1af4afa179d92bf9c7b13e2784984f2ae52fcc65312d361004  iDuanJu-1.5.2-android-release.apk
```
