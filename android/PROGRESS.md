# Android 进度

## 时间线（Asia/Shanghai）
- 2026-11-11 ~00:20：M0/M1 骨架 + 红果 core 完成；单元测试通过；红果 live 实测完成。

## 里程碑
- [x] M0 环境（JDK 21、SDK 35、NDK、Gradle 8.11、/dev/kvm 存在；模拟器 adb 曾 offline，改用 TCG 启动中）
- [x] M1 core：红果签名（Gorgon 金值对齐）、设备注册、榜单、详情、resolvePure、CENC 全文件解密
- [x] M1 app：主题 / 玻璃 Tab / 首页 / 详情 / 播放器（ExoPlayer + CENC 下载解密）/ 设置里的设备覆盖
- [ ] M2 Infuse 手势 / HLS 去广告接入播放 / CMS
- [ ] M3 逐屏对比修 UI（模拟器截图待 TCG 启动）
- [ ] M4–M6 搜索 Medusa / 插件 / TG …

## 红果软拒绝证据（必读）
- **现象**：mint 设备走 resolvePure / batch / single → 全部 `HTTP 200` + **0 bytes**（软拒绝）。
- **证据**（JVM live，`-Plive=1`，无 override）：
  - `[HG] resolvePure HTTP 200 empty level=4 did=893842861941472 bytes=0`
  - `[HG] player batch HTTP 200 empty form=ab_off|ab_on`（两次 remint 后仍空）
  - `[HG] resolveSingle HTTP 200 empty (soft reject)`
  - 最终：`上游错误：取流被上游空包拒绝（软拒绝）`
- **可用路径**：设置 `hg.purePlayerDevice=did,iid`（公开 iOS 同款覆盖，**不写进源码**）。用网关已知可用对测：
  - `[HG] resolvePure ok level=3 streams=1 did=9911…`
  - media probe HTTP 200，CENC 解密 8.9MB 成功。
- **原因对照**：与 iOS 私有 `863f680` 一致——`resolvePure` 对新 mint 设备软拒绝；私有 Swift 写死 known-good，公开仓库用设置覆盖。Android 公开代码与公开 Swift 对齐。

## 测试
- HGSignGoldenTest / HLSAdFilterTest：PASS
- HongguoLiveTest.deviceRegisterAndRank：PASS（device_register + 热播榜 12 条）
- HongguoLiveTest.detailAndResolve：mint FAIL（软拒绝）；override PASS
- CENCLiveTest：PASS（下载+解密）

## APK
见 `/workspace/iduanju-android/dist/`
