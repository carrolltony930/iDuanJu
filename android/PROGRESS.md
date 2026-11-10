# Android 进度

## 已推送
- https://github.com/my-name-is-alan/iDuanJu/commit/bfb1aa5 （main）

## 里程碑
- [x] M0 环境（JDK 21、SDK 35、Gradle 8.11；/dev/kvm 存在但加速启不动，TCG 可装 APK）
- [x] M1 core 红果：签名金值、设备注册、榜单、详情、resolvePure、CENC 解密
- [x] M1 app：主题 / 玻璃 Tab / 首页 / 详情 / ExoPlayer+CENC / 设置覆盖
- [ ] M2 Infuse 手势完善 / HLS 去广告进播放 / CMS
- [ ] M3 UI 1:1 对比修差异（模拟器无外网，数据截图需真机）
- [ ] M4–M6 Medusa 搜索 / 插件 / TG / 黄果 / 弹幕

## 红果软拒绝（已实测）
| 路径 | 设备 | 结果 |
|---|---|---|
| resolvePure / batch / single | 本机 mint | **HTTP 200 + 0 bytes**（软拒绝），见 live 日志 |
| resolvePure | 设置覆盖 did/iid | **ok streams=1**，媒体 HTTP 200，CENC 全文件解密成功 |

公开代码不写死设备；「我的」里可填 `device_id,iid`（同公开 iOS）。

## 测试
- HGSignGoldenTest / HLSAdFilterTest：PASS
- HongguoLiveTest.deviceRegisterAndRank：PASS
- detailAndResolve：mint FAIL（软拒绝证据齐全）；`-PhgDevice=` override PASS
- CENCLiveTest：PASS（~8.9MB）

## APK（盒子）
`/workspace/iduanju-android/dist/`
- debug 21.8MB SHA256 3cdc5e6c…
- release 14.9MB SHA256 f974a44c…

## 模拟器
- 曾安装成功并截到首页 UI 壳（搜索条/榜单 chip/玻璃 Tab）
- 模拟器无外网（Network is unreachable），无法在模拟器内完成红果取流 UI；**红果端到端在盒子 JVM live 测试已证明**
- 真机需再验：首页有网加载、详情、播放、设置覆盖
