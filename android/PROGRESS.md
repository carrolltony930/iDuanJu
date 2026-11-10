# Android 进度

更新：2026-11-11 02:20（Asia/Shanghai）

## 已推送（main）
- bfb1aa5 骨架 · 93a2757 首页/详情/播放/片库/Medusa/搜索 · 3d26339 HLS 去广告移植/找剧/调试 E2E
- 本次：软拒绝研究 + one-shot 设备（无需覆盖即可取流）+ 模拟器联网 + UI 修正

## 里程碑
- [x] M0 环境（JDK 21、SDK 35、Gradle 8.11；KVM 卡死，只能 TCG）
- [x] M1 core 红果：签名金值、设备注册、榜单、详情、resolvePure、CENC 解密
- [x] M1 app：主题 / 玻璃 Tab / 首页 / 详情 / ExoPlayer+CENC / 设置覆盖
- [~] M2 播放器手势 ✅、HLS 去广告（逐条移植 + 10 测）✅、CMS ❌
- [~] M3 UI 1:1：首页/详情/播放器 已在模拟器真数据对比（docs/screenshots/）；差异见下
- [~] M4 Medusa ✅、签名搜索 ✅、筛选找剧 ✅；插件/TG/黄果/漫画/弹幕 ❌

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

## UI 对比（docs/screenshots/，模拟器真数据）
- 首页：结构与 iOS 一致（搜索条/源栏/chip/海报 Hero/继续观看/热播榜）；修了 Hero「查看详情」被挤出。
- 详情：标题/元信息/播放胶囊/收藏/主演/简介/选集一致；iOS 有题材标签行与「系列·共N季」，本条剧数据下 Android 未显示（待查）。
- 播放器：顶栏/中控/底部信息/工具栏一致；技术标签只显示 1080P → 已改为首帧后刷新；iOS 有「S8 ·」季标、弹幕层，Android 缺。

## 测试（2026-11-11 02:00）
- HGSignGoldenTest 2、HLSAdFilterTest 10、MedusaTest 1：PASS
- HongguoLiveTest 3（注册+榜单、详情+取流+媒体、签名搜索）：PASS（无覆盖）
- CENCLiveTest：PASS（无覆盖，route=oneshot）
- 模拟器 E2E：RESULT OK（注册→榜单 12→详情 202 集→取流 1080p bytevc1 enc→下载解密 8,937,645 B ftyp）

## 待做
- CMS（MacCMS）/ QuickJS 插件 / TG / 黄果 / 漫画 / 弹幕 / 授权页 / 搜索系列落地 / 流式 CENC 代理
- 季标、详情标签与系列行
- 真机：实际播放流畅度、PiP、HEVC 硬解、one-shot 在移动网络下的成功率
