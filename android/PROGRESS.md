# Android 进度

更新：2026-11-11 03:15（Asia/Shanghai）· 断点续做：先看「当前进行中」

## 当前进行中
- 真数据 iOS↔Android 对比（shots/compare/，本地；仅非成人、非采集站截图进仓库 docs/screenshots/）
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
| 漫画插件 / 漫画阅读器 | ❌（manifest 可装，阅读器未移植） |
| 弹幕 | ❌（按钮在，弹幕层未移植） |
| TG / 黄果 / 频道页 | ❌ |
| 插件订阅 / 签名校验 / CF 验证 / 执行超时中断 | ❌（QuickJS 包装库无中断钩子：超时即弃用实例） |

## 提交（main）
- 59f5f9d 软拒绝根因 + one-shot 设备 + 模拟器出口开关 + UI 修正
- 34a78e6 详情增强（web _ROUTER_DATA、系列落地、关联季、SeasonParser）、播放器季标、Hero 简介自适应
- bcaa66b 苹果 CMS 适配 + ContentFilter + NetGuard + HLS 本地清单去广告
- 682f7f0 idj1 插件宿主 + 引擎无关运行时 + 视频映射 + 测试
- e057cd7 QuickJS 引擎、PluginStore、plugin: 路由、播放器请求头、调试自测
- 524b52d 「我的」插件管理；简介实体清理

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

## 测试（2026-11-11 03:00）
- 离线：HGSignGolden 2、HLSAdFilter 10、Medusa 1、MacCMSTest 2、PluginHostTest 4（manifest/域名策略/crypto/html/m3u8/unpack/storage/重定向拦截/限流；3 个内置 iOS 插件均可解析）：全部 PASS
- 在线（-Plive / -PcmsApi，地址只在运行时传入）：HongguoLiveTest 3、CENCLiveTest（route=oneshot）、MacCMSLiveTest、PluginHostLiveTest：PASS
- 模拟器（AVD idj30，API 30 x86_64，TCG，经宿主代理）：
  - 红果 E2E RESULT OK（注册→榜单→详情 202 集→1080p bytevc1 加密→解密 8,937,645 B ftyp），UI 播放 HEVC
  - CMS：源栏「采集A」、详情、HLS 播放
  - 插件：`--es pluginSelfTest 1` → SELFTEST OK（真 QuickJS：async/await、html、aes、storage、域名拦截 E_PERMISSION、宿主错误码 E_NOT_FOUND）
  - 插件实网：本地桥接插件（不进仓库）→ 列表 20 / 分类 20 / 详情 8 集 / play→HLS 去广告 14 段 → UI 播放 0:27

## 真机检查清单
- 播放流畅度、PiP、HEVC 硬解、CENC 解密速度
- one-shot 设备在移动网络 / Wi-Fi 下的成功率（模拟器 11/12）
- CMS HLS 播放与去广告；插件 QuickJS 在 arm64 上加载与执行耗时
- 深色/浅色主题、刘海屏、横屏

## 待做
- 对比截图：搜索/选集面板/频道面板；差异修正
- 弹幕层、漫画阅读器（插件 pages）、TG、黄果、频道
- 插件订阅/推荐插件、执行超时中断

## APK
构建于 built 524b52d 03:12:14（release 用 Android Debug 证书签名；未混淆）
```
iDuanJu-1.5.2-android-debug.apk 28632859
iDuanJu-1.5.2-android-release.apk 20382519
5708e2624b84d8b78253af8a1af6f8a41af42c55af9510a1352cc21625fa2981  iDuanJu-1.5.2-android-debug.apk
b803448671475dd235067dccaf0438ae17c0a1c4dc17421917f02aff24927010  iDuanJu-1.5.2-android-release.apk
```
