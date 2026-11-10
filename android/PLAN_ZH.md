# i短剧 Android 版计划（Kotlin + Jetpack Compose）

> 计划 / 审阅：高阶模型；执行：普通模型。每个里程碑结束按本文件逐项自审，结果写进 PROGRESS.md。
> 功能真相来源：iOS 代码（公开 main 的 `STV/` + 私有 `feat/multi-source@863f680` 的新行为，只读）。
> 公开仓库纪律：不写死红果设备 ID（每台设备本地生成，设置里可覆盖）、不放服务器 IP / 私有域名 / 本地路径 / 账号 / 密钥；每次 push 前 gitleaks + trufflehog；不强推 main；截图只放非成人内容。

## 1. 工程结构
```
android/
  core/   纯 Kotlin/JVM 模块（可在盒子 JVM 上直接跑单测和联网测）
          hongguo/（签名 HGSign、HGPlayerSign、Medusa、设备、客户端）  cenc/（CENC AES-CTR 解密 + range plan）
          hls/（HLSAdFilter）  cms/（苹果 CMS 适配器）  model/  util/（J、Bytes、GoURL）
  app/    Android 应用：Compose UI、Media3 播放器、Room/DataStore、QuickJS 插件运行时、TDLib（后续）
```

## 2. 模块对照清单（iOS → Android）
状态：☐ 未做 / ◐ 部分 / ☑ 完成（以 PROGRESS.md 为准）

| # | iOS 模块（文件） | Android 对应 | 备注 |
|---|---|---|---|
| 1 | Views/Theme.swift STVTheme（bg/surface/elevated/text/secondary/accent/accentWarm，跟随系统深浅色） | ui/theme/Theme.kt | 颜色逐值照抄 |
| 2 | RootTabView + GlassTabBar（悬浮玻璃胶囊 4 tab：首页/搜索/找剧/我的，二级页隐藏） | ui/RootScreen.kt | 高 48、内边距 6、最大宽 400、阴影 |
| 3 | HomeView：搜索胶囊 + 筛选、源分类条（左右滑切换、选中自动居中）、Hero（模糊背景+完整竖封面+文字+分页点）、继续观看、榜单分区（真实榜名）、3 列竖海报、只显示「全N集/更新至N集」角标 | ui/home/ | |
| 4 | DramaDetailView：大图头 + 渐变、标题、元信息、继续第N集 + 收藏、标签、主演、简介、系列/季、选集 | ui/detail/ | |
| 5 | SearchView（筛选：全部/真人剧/漫剧/AI剧）、搜索历史 | ui/search/ | 依赖 Medusa 签名 |
| 6 | CategoryView 找剧（panel 筛选行 + 分类结果） | ui/category/ | |
| 7 | ProfileView 我的：历史、继续观看、片源管理、插件、设置、开源许可 | ui/profile/ | |
| 8 | ActorRankView / 演员页 / ActorSeasonViews | ui/actor/ | 后续 |
| 9 | 红果 HongguoClient（rank/search/detail/panel/category/landing/web/celebrity/resolve）+ HGSign + HGPlayerSign + Medusa + HGDevice + HGPlayerDevice + HGStreamExtras（缓存/预取/fallback_api/key_seed） | core/hongguo | 金值测试对齐 Swift |
| 10 | CENCDecryptor + 本机代理边下边解 | core/cenc + app DataSource | Android 用自定义 DataSource 流式解密（等价于 iOS loopback proxy），另保留 loopback 代理 |
| 11 | 黄果 HuangguoClient（AES 封面 coverSrc/posterSrc） | core/huangguo | 后续 |
| 12 | Telegram：t.me/s 公开频道解析（TGParser/TGStore/TGFilter 关键词/正则屏蔽/频道搜索）+ TDLib 登录（自填 api_id，持久会话）+ 边下边播（分块 range） | app/telegram | TDLib 用官方 Android 预编译 / 后续 |
| 13 | 漫画（MangaStore/Providers/Views，捏合缩放，翻页区） | app/manga | 走 JS 插件 |
| 14 | Cloudflare 静默过盾（隐藏 WebView → 拿 cookie/UA 重试）SiteCF / PluginCF / SiteVerifySheet | app/cf | |
| 15 | 插件系统 idj1（docs/plugins/SPEC_ZH.md）：QuickJS 每插件独立 context、超时、内存上限、域名白名单（重定向复检）、禁 loopback/私网（放行 198.18/15）、独立 cookie/存储/WebView 数据、@@secret@@ 占位、不可访问 TG/红果/密钥库；图片描述符 stripes/aes；苹果 CMS 内置适配器；手动添加 + 订阅；管理 UI | app/plugins + core/cms | QuickJS 用 quickjs-ng 绑定（JNI） |
| 16 | 播放器 PlayerV3/VerticalPlayerView/PlayerChrome：细进度+缓冲条、技术信息行、右侧玻璃菜单（倍速/清晰度/音轨/字幕/弹幕/选集）、默认竖屏、旋转/手动横屏、上下滑切集、左右边缘亮度/音量（退出还原亮度）、单击即时、两侧双击累加快进退、捏合铺满/适应、长按倍速、PiP、后台播放 + MediaSession | app/player | Media3 ExoPlayer |
| 17 | HLSAdFilter（与 iOS 同规则，移植 Swift 单测）+ 去广告代理 | core/hls | |
| 18 | Prefetch：3–4 集预载、500–1000MB 预缓存可配 | Media3 SimpleCache + CacheWriter | |
| 19 | 弹幕 HGDanmaku + DanmakuViews（按屏幕布局、显示区域 1/4、1/3、1/2、3/4） | app/player/danmaku | |
| 20 | 历史 / 继续观看 / 收藏（LibraryListView） | app/data（JSON / DataStore） | |
| 21 | 设置：主题、播放内核信息、去广告、预缓存大小、红果设备覆盖、弹幕区域 | ui/settings | |
| 22 | 开源许可页 | ui/licenses | |

## 3. 依赖映射
| iOS | Android |
|---|---|
| SwiftUI | Jetpack Compose（Material3，自绘玻璃效果：半透明 + 描边 + 阴影；Android 12+ 用 RenderEffect 模糊） |
| AetherEngine / AVPlayer | Media3 ExoPlayer（含 HLS、MediaSession、PiP） |
| URLSession | OkHttp 4（红果 player 会话不走系统代理：Proxy.NO_PROXY） |
| CryptoKit / CommonCrypto | javax.crypto（AES/CTR/CBC）、MessageDigest（MD5/SHA256/SHA512），SM3 自实现 |
| QuickJS（vendored quickjs-ng） | quickjs-ng 的 Android JNI 绑定（如 `io.github.dokar3:quickjs-kt` 或自带 JNI） |
| TDLibKit | TDLib 官方 Java/JNI（后续里程碑） |
| WKWebView（CF） | android.webkit.WebView + CookieManager |
| Kingfisher/自写 CoverImage | Coil（自定义 Referer header 拦截器） |
| UserDefaults / JSON 文件 | SharedPreferences / JSON 文件（filesDir） |
| XCTest / XCUITest | JUnit（core 在 JVM）、Robolectric/Compose UI test、模拟器 instrumented test |

## 4. 里程碑
- **M0** 环境：JDK 21、SDK 35、NDK、Gradle 8.11、x86_64 模拟器（/dev/kvm 可用）。
- **M1** 骨架 + 主题 + 玻璃 Tab + 红果首页/详情（rank、detail、directory、landing、web）+ 播放（resolvePure → CENC 流式解密 → ExoPlayer）。**红果端到端在模拟器上实测**（首页、详情、取流、真实播放），记录软拒绝证据。
- **M2** 播放器 Infuse 风格 UI、手势、倍速、切集、预载/预缓存、HLS 去广告（含单测）、苹果 CMS 源。
- **M3** iOS/Android 逐屏截图对比并修差异（shots/compare/）。push + 出 APK（早 08:00 前必须到这里）。
- **M4** 搜索（Medusa）、找剧、演员、历史/收藏、设置/许可完善。
- **M5** 插件系统（QuickJS + idj1 + 漫画插件 + 管理 UI）、CF 过盾。
- **M6** Telegram（公开频道 + TDLib）、黄果、弹幕。

## 5. 每个里程碑的自审要点
1. 对照第 2 节逐项更新状态；2. 单测全过；3. 模拟器上实操截图；4. gitleaks + trufflehog 新提交；5. 搜索仓库确保无写死设备 ID / IP / 私有域名。
