# i短剧 Android

原生 Kotlin + Jetpack Compose，目标与 iOS 功能对齐。计划见 [PLAN_ZH.md](PLAN_ZH.md)，进度见 [PROGRESS.md](PROGRESS.md)。

## 要求
- minSdk 26 / targetSdk 35
- JDK 21（字节码目标 17）

## 构建
```bash
cd android
./gradlew :app:assembleDebug :app:assembleRelease
```
APK：`app/build/outputs/apk/{debug,release}/`

## 红果取流
公开构建**不写死**设备 ID。上游会软拒绝未注册的本机设备（HTTP 200 空包），默认每次取流现注册一台一次性匿名设备（只用一次）；也可在「我的」填入自己的 `device_id,iid` 覆盖（同 iOS `stv.hg.purePlayerDevice`）。详见 [PROGRESS.md](PROGRESS.md)。

## 测试
```bash
./gradlew :core:test
./gradlew :core:test -Plive=1                          # 联网：注册+榜单
./gradlew :core:test -Plive=1 -PhgDevice=did,iid       # 联网：取流（需可用设备对）
```
