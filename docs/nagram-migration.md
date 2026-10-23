# Nooagram → Nagram 迁移记录

更新时间：2026-10-23。**迁移尚未完成，不可发布。**

## 基线与工作范围

- 工作分支：`nooagram-nagram-migration`，独立工作目录，不在原 XF 目录上覆盖。
- 新上游：`NextAlone/Nagram` 的稳定 `main`。
- 固定提交：`87863c8a57854c739e1452e56591c07ea52415d3`。
- 本次选定基线：Nagram 1255 / Telegram 13.0.2（7175），不是持续变化的 dev 分支。
- 原 Nooagram 基线：`16e82226b63cab5fbd6a0d6028c5d86002176856`。
- 已按用户要求生成并安装独立 debug 测试 APK；当前可用构建仅 ARM64，初版 ARMv7/ARM64 均已撤回。尚未提交、推送或发布迁移版。手机正式应用数据及原目录的本地签名文件未动。
- 工作树共享 Git remotes；`origin` 仍可能指向旧 XF。不要直接 `git push origin`。新增同步脚本会验证推送目标，普通终端操作也必须先确认目标。

## 已接入的代码（不等于已完成真机验收）

| 模块 | 接入范围 | 当前验证边界 |
| --- | --- | --- |
| AyuMoments 设置 | 独立 AyuConfig、旧偏好键兼容、原生 Nagram 配置代理、设置入口和语言资源 | Java/Kotlin 编译；设置页面仍需逐项真机检查 |
| 归档与编辑历史 | Room 实体/DAO、21→35 迁移、历史页面、删除/编辑/附件相关消息管线 | schema 35 合成旧库读写、分页/删除/附件回归；不是实际用户数据升级测试 |
| 过滤与主页预览 | 正则规则、用户/频道过滤、规则迁移、预览回退、缓存失效及多账号刷新 | 单元测试及预览集成探针；滚动、时间残留仍需真机观察 |
| 幽灵模式 | 按账号读取的控制器、消息回执相关挂接、状态图标与界面刷新 | 源码接入；多账号切换和实际收发尚未验收 |
| 转发 | 中央发送入口、顺序发送、媒体预下载、付费确认兼容挂接、取消/退出账号保护 | 取消回归通过；混合分块、付费、超时及进程恢复待端到端验证 |
| 媒体 | 附件归档、自定义保存目录、分聊天子目录、PhotoViewer 单/批量保存路径 | 路径及清理回归通过；系统相册/存储权限/真实旧媒体待测 |
| 搜索 | 归档结果合并、按会话+消息 ID 去重、服务器游标独立、过期回调保护 | 合并单元测试；本地归档暂为每来源最多 200 条，无完整归档翻页 |
| 置顶与弹出菜单 | 跳转代次和动画抑制、顶栏更新抑制、弹窗圆角背景/阴影修复 | 25 项源码/逻辑探针；不代表已验证无闪烁/无阴影 |
| 更新器 | 保留 Nooagram manifest、ABI 选择、下载取消/替换保护、来源限制 | 下载及 URL 单元测试；真实更新安装尚未验收 |

旧 `com/radolyn/ayugram` 目录的 59 个 Java 文件均有迁移目标；另有从 XF 隔离出来的设置、组件及适配类。文件齐全不代表每一项运行行为已经等价。

搜索约束：Saved Messages / monoforum / forum 使用持久化 topicId 范围；普通回复线程尚未实现归档线程查询，因此不混入整聊天归档。reaction 和非空媒体筛选不混入归档。

## 数据和版本保护

- applicationId 保持 `fork.yuhuan.nooagram`，偏好文件保持 `nkmrcfg`，归档库名保持 `ayu-data`。
- Room 仍为 schema 35，identity hash 为 `388c57706282c0f638478a2af2440240`。Room 编译器使用 kapt，否则 Kotlin/ObjectBox 构建中可能漏生成 `AyuDatabase_Impl`。
- 不启用破坏式降级重建。ObjectBox 打开失败不再自动清空目录，也不删除旧 XF SQLite 缓存。旧 SQLite 缓存到 ObjectBox 的跨引擎转换尚未实现。
- 上一已发布 versionCode 基线 `125000081`；迁移版本下限 `125001000`，由 `nooagram-version.json` 管理。
- 当前预发布名 `1.3.0-migration-13.0.2.n1255+125001000`，不是已发布正式版号。
- `migration_ready=false` 阻止 release 的 assemble/package/bundle/publish。Java 编译、测试、R8 检查不因此被阻止。禁止为绕过检查而临时改为 true。
- debug 测试包使用 `fork.yuhuan.nooagram.migrationtest` 与 Android debug 签名，显示名 `Nooagram Migration Test`，不覆盖正式版。独立数据目录需要重新登录。测试版禁用备份、Firebase 自动初始化/推送以及正式版更新检查。
- 选定的新上游最低 Android 版本为 API 27（Android 8.1），不能据此承诺旧版支持的全部设备均可继续升级。
- `LockedDao` 通过动态代理调用 Room DAO。已为 DAO 接口及实现添加 R8 保留规则，防止接口合并导致 Release 运行时失败；验证必须检查优化后的 DEX，不能只看编译成功。

## 自动跟随新上游

- 旧上游 debug/pr/release 工作流已移至 `.github/upstream-workflows/*.disabled`，避免误触旧频道或旧发布配置。
- `migration-checks.yml`：PR/手动触发，只读，迁移范围单测、集成探针、Release R8 与 DEX 反射契约检查；不是全仓库所有测试通过的声明。
- `sync-upstream.yml`：每 6 小时或手动检查，但仅在 Nooagram 仓库且变量 `NOOAGRAM_NAGRAM_SYNC_ENABLED=true` 时执行。未推送、未启用。
- 同步脚本只创建 `sync/nagram-*` 审查分支和指向 `ayu` 的 PR，不直接合并 `ayu`，不强推，不自动发布。
- 检查 origin、稳定分支祖先关系和版本上限；冲突则中止合并并报告 issue；推送后 PR 创建失败可以重试；遇到查询错误不继续推送。
- GitHub token 创建的 PR 可能不会自动触发其他工作流，需手动执行迁移检查。没有建立替代的正式签名/发布流水线。

## 验证命令

需要 JDK 21、Android SDK，以及 `TMessagesProj/lib/jlatexmath`、`TMessagesProj_Modules/media` 两个 Java 子模块。以下不生成可安装包。

```powershell
./Tools/check-nagram-migration.ps1
./gradlew.bat :TMessagesProj:testDebugUnitTest --tests "com.radolyn.ayugram.*" --tests "tw.nekomimi.nekogram.filters.*" --tests "tw.nekomimi.nekogram.helpers.remote.*" --console=plain
./TMessagesProj/src/test/run-preview-regressions.ps1
./Tools/ayu-privacy-history-regression/run.ps1
./Tools/media-regression/run.ps1
./Tools/pinned-settings-regression/run.ps1
node Tools/nagram-sync-regression/test.cjs
./gradlew.bat :TMessagesProj:minifyReleaseWithR8 --console=plain
node Tools/check-ayu-release-dex.cjs
```

Linux CI 使用 `./gradlew` 代替 `./gradlew.bat`，其余 PowerShell 脚本可在 pwsh 运行。上游提案测试使用隔离 git/gh 替身，不联网，不修改真实仓库或创建真实 PR。

本轮结果：

- 迁移范围单元测试：91 项，0 失败；包括搜索合并、更新版本/来源、取消发送、旧 schema 35 合成数据库，以及测试包隔离/不拉取正式更新/不注册 Firebase。
- 预览：30 项探针通过。
- 数据/隐私历史：10 个用例、302 个断言通过。
- 媒体路径：3 项通过。
- 置顶/设置：25 项通过。
- 上游同步：11 个隔离场景通过。
- 稳定提交及 versionCode 检查通过，`git diff --check` 无空白错误。
- 全仓库单测曾运行 114 项，其中 107 通过、7 失败：InputTextAnimation 3 项受 JNI/NaConfig 初始化影响；OpenCC 及 DNS 各 1 项因缺少 `libtmessages.49.so` 无法初始化；CarVoiceAttachment 2 项文件 URI 解析失败，尚未确认是否仅 Windows 测试环境问题。不能称为全套通过。OpenCC 测试本身会写语言资源，不应盲目在工作树重复全套测试。
- Release R8：实际执行成功；检查优化后 3 个 DEX，确认 5 个 DAO 接口、5 个生成实现及 `AyuDatabase_Impl` 保留，反射调用所需方法保留。修改前检查能复现 DAO 接口缺失，修改后通过。未生成 APK，尚不代表 Release 真机运行通过。
- `packageRelease --dry-run` 按预期被未完成迁移保护拒绝，且拒绝原因已核验。
- 2026-10-23 已连接 ARM64 真机，复现初版测试包 JNI 签名不匹配导致启动崩溃；已完成 ARM64 源码重编、覆盖安装和三次真机冷启动验证。

本地详细日志位于迁移工作树上两级的 `migration-*.log`，Gradle 报告在 `TMessagesProj/build/reports/tests/testDebugUnitTest/`。这些是本地验证记录，不是 CI 已成功运行的记录。

## 发布前必须完成

### 测试包原生库与启动修复

初版 outputs/Nooagram-Nagram-test/ 中两个 APK 已撤回，不要继续安装。它们虽然通过 APK 签名和哈希检查，但真机发现：上游 libtmessages.49.so 要求证书 SHA1 3A0F57FE06485D0B90D0ACD990E3A30328E3988D，本地 debug 证书不同，导致 JNI_OnLoad 返回 JNI_ERR。同提交/同 ABI/同哈希不代表签名绑定兼容。

现已禁止 NOOAGRAM_USE_PINNED_NATIVE=1；旧准备脚本也直接拒绝复用官方成品库。必须从当前固定源码构建。原生签名校验保留，由 CMake 参数 NOOAGRAM_EXPECTED_SIGNER_SHA1 绑定当前 APK 证书；空值或非法值拒绝配置。debug 自动读取 .android/debug.keystore 的公开证书，不读取正式签名文件。release 需提供经审核的 NOOAGRAM_NATIVE_SIGNER_SHA1（公开证书 SHA1），不可使用上游签名值冒充自身身份。

本地 ARM64 debug 构建（JDK 21 / Android NDK 27.2.12479018 / PowerShell）：

    # 首次初始化固定子模块（不使用 --remote），并在干净 tgcalls 中应用仓库补丁。
    git submodule update --init --depth 1 --jobs 6
    # 先在 TMessagesProj/jni 中执行 patches/tgcalls.sh；勿重复打补丁或清理用户改动。
    $env:COMPILE_NATIVE="1"
    $env:NATIVE_TARGET="arm64-v8a"
    Remove-Item Env:NOOAGRAM_USE_PINNED_NATIVE -ErrorAction SilentlyContinue
    ./gradlew.bat :TMessagesProj:assembleDebug --console=plain
    ./Tools/check-migration-native-signer.ps1 -Apk <生成的APK绝对路径>

check-migration-native-signer.ps1 先验证 APK 签名，再检查每个打包 tmessages 库是否绑定同一证书并拒绝上游证书。已确认旧崩溃 APK 被该回归检查拒绝；此检查仍不能替代真机启动。还修复了 NativeLoader 回退对绝对路径误用 System.loadLibrary 的问题，改用 System.load，不吞掉 JNI 加载失败。

测试包继续使用独立包名、debug 签名、独立数据目录，不覆盖正式版。Firebase 后台推送和正式更新检查仍禁用。首次启动只检查欢迎/登录页面，不自动输入手机号、登录、发送消息或读取聊天数据。

### test2 ARM64 真机启动验证结果（2026-10-23）

- 原生库由本机 Windows NDK/CMake 从固定源码编译，匹配 Android debug 证书。还修复 NDK cpufeatures 绝对路径过长导致的 Windows Ninja mkdir 失败，未修改 SDK 源码。
- 新包 82,210,459 字节，保存在工作树上两级 outputs/Nooagram-Nagram-test2/。仅 ARM64；ARMv7 旧包仍撤回。
- 实际 adb install -r 覆盖同签名独立测试版成功；正式应用和数据未动。
- 连续三次 force-stop 后冷启动，PID 分别 9189、19705、20018，每次十秒后仍在前台。日志确认“签名一致”，未发现 JNI_ERR、FATAL EXCEPTION 或 Fatal signal。
- 已检查 UI hierarchy 和截图：正常欢迎页，显示 Start Messaging 和简体中文入口，没有自动登录。
- 新增 Tools/test-migration-startup.ps1 固定只测试 migrationtest 包，保留单应用 PID 日志、启动状态及 JSON 结果；不清 logcat、不清数据、不卸载应用。
- 迁移范围单测重跑 91 项零失败，预览 30 项探针重跑通过。旧 APK 原生证书不匹配被回归脚本拒绝，CMake 对空/短/非十六进制指纹均拒绝。
- 这里只验收启动修复，不代表登录、旧数据覆盖升级、AyuMoments 全部功能或正式发布通过。欢迎页部分品牌仍来自上游，待后续迁移清理。

### test3 语言修复与真机反馈（2026-10-23）

- `LOC_ERR` 的原因是新上游二进制语言加载遗漏 Ayu namespace，另有十个动态配置键未迁移。`BundledLocalization` 统一加载英文 fallback 与当前语言的 Na/Neko/Nekox/Ayu；七个 Ayu 语言包补齐动态键。
- 新增四项语言回归（七种语言、英文 fallback、资源 ID 和实际打包二进制），迁移范围单测合计 95 项零失败。test3 已覆盖安装独立测试包，用户已自行登录。
- 真机 UI hierarchy 已确认 AyuMoments 主设置可见区域包含中文“间谍设置”“置顶隐藏”“自定义”“使已删除的消息半透明”“禁用广告”“使用自定义保存目录”，无 `LOC_ERR`。子页尚未逐项验收。
- 流畅度两次独立采样分别约 27 秒和 36 秒，但 gfxinfo 均报告 0 新帧；4950ms 百分位为无样本占位，不能解释为实测延迟，也不能用 0% jank 宣称流畅。采样期间显示模式为 60Hz；不能据此断言有效滚动仅 60Hz。
- 用户随后报告卡死退出。10:30:01 的主线程崩溃堆栈确认 `AyuOptions$1.String()` 将布尔默认值强转字符串，调用来自设置项长按菜单 `getRowValue()`；10:30:06 系统记录输入超时 ANR，10:30:11 进程退出。已将故障定位到类型转换，修复和 test4 验证进行中。此前电脑点按无页面变化的原因仍未确认，不能归咎于 USB 权限。
- 这次测试发现稳定性缺陷，流畅度尚未通过验收。

### test4 设置触摸与长按修复（2026-10-23）

- AyuMoments 及其使用共用设置基类的子页，在触摸按下时检查开关实际位置；只有开关附近最小 48dp 触摸区域可切换，文字和说明不切换。RTL 使用控件实际坐标，多行说明不会让整条右侧区域变为开关。普通导航行保持整行可进入。
- 折叠分组的文字只展开/收起，独立开关区域才批量切换；弹窗和附件范围复选框也限制触摸区域，移动超过 touch slop 或收到取消事件后不切换。键盘/无障碍点击保留。
- “保存附件”的详细配置改为独立导航入口；图标开关创建真实 Switch，复用为导航行时隐藏开关，防止入口失去点击响应。
- `AyuOptions.String()` 仅对字符串配置读取 String 偏好；其他类型通过类型对应 getter 序列化，消除设置长按菜单中的 Boolean→String 强转崩溃，也保留云配置及上游委托的最新值。
- 本机迁移范围 110 项单测零失败、零跳过，其中触摸事件链 10 项、长按值序列化 5 项。Robolectric 隔离主题壁纸、原生动画及网络初始化，真实设置控件和 RecyclerListView 触摸链仍参与测试。这不是全部真机功能或流畅度验收。
- test4 ARM64 APK 已生成至工作树上两级 `outputs/Nooagram-Nagram-test4/`，签名与原生库证书匹配检查通过，已 `adb install -r` 覆盖独立测试包。真机冷启动十秒后仍在前台（PID 31480），未出现该次启动的 JNI/FATAL 错误。
- 当前 ADB 注入设置导航点击后，UI hierarchy 未发生变化；真机触摸验收等待用户打开 AyuMoments 页，不能据此宣称实机误触、长按或流畅度已验收。`migration_ready=false`。

### test5 长附件目录名布局修复（2026-10-23）

- 真机复现：间谍设置的“附件文件夹”右侧长名称覆盖左侧标题。仅设置 `AnimatedTextView.setMaxWidth()` 无效：`adaptWidth` 在测量末尾重新采用旧 drawable 宽度，且文字未变化时 drawable 没有按新宽度重建。
- `TextSettingsCell` 在两种布局模式下都为标题预留空间，限制右侧值至可用宽度的一半；清空无值行的旧文本。`AnimatedTextView` 自适应测量遵守宽度上限，非动画文本在可用宽度变化时重新布局。
- 新增 5 项布局回归，覆盖 debug/release 布局、RTL、相同文字缩窄/扩大，以及控件复用；修复前 4 项失败，修复后全部通过。迁移范围合计 115 项单测零失败、零错误、零跳过，非全仓库测试声明。
- ARM64 debug APK 构建、签名与原生库证书匹配检查通过，已覆盖安装独立 `migrationtest` 包。当前手机上通过右上角菜单进入设置；底栏设置点击无响应不在本次修复范围内。
- 真机截图和 hierarchy 确认标题范围 x=108..629、右侧值 x=641..1185，长值显示省略号；上下滚动、返回 AyuMoments 再进入后仍不重叠。此处控件复用由单测覆盖，不将滚动后仍在屏幕内的行误称为真机已触发回收。
- 本次导航/滚动期间测试进程 PID 保持 29052，检查该进程日志未发现 FATAL/JNI/类型转换崩溃；这不代表长期稳定性或流畅度验收。
- 安装包及截图保存在工作树上两级 `outputs/Nooagram-Nagram-test5/`。未提交、推送或正式发布，`migration_ready=false`，正式版应用和数据未动。

### 正式发布仍需完成

1. 正式流水线从固定 Nagram 源码、子模块及补丁构建原生库，绑定 Nooagram 自己的正式公开证书指纹。不能直接复用官方成品库或 XF 旧 .so。上游初始化脚本会清理子模块/输出，禁止对含用户改动的目录盲目运行。Windows 已使用 Android NDK/CMake 源码构建，无需为此启用系统虚拟化。
2. 完成安装包构建、Release 真机验证和签名证书一致性验证；R8/DEX 静态检查已通过。**包名相同不代表可覆盖升级**。密钥不得提交或复制到工作树；新签名流水线需使用受保护的密钥来源。
3. 用测试账号/测试设备验证首次启动、登录、退出重登、多个账号、Nagram 设置和 AyuMoments 全部选项；没有备份前不卸载正式客户端、不清数据。
4. 在备份副本验证旧版升级：消息及编辑归档、二进制 TL 媒体、附件、过滤规则、话题/频道私信、备份导出/导入/失败回滚。合成 schema 测试不能替代真实签名升级。
5. 真机录屏检查主页过滤回退/时间残留、置顶跳转闪烁、菜单上沿阴影，以及聊天的透明度/已删除标记/富文本。
6. 验证普通/归档混合转发、付费确认、取消/断网/退出账号，及保存相册、媒体下载、多账号通知。
7. 处理上述 7 项完整测试失败；审核本地搜索分页上限、旧缓存转换、图标品牌、无用兼容代码及其他未验证路径。
8. 建立 Nooagram 自己的正式构建、发布和 update.json 产出流程，审核后才提交迁移分支/PR，最后决定是否启用上游同步。

回退必须保留升级前 APK、签名来源与可恢复的数据备份。Android 通常不允许直接覆盖降级；不要用卸载正式客户端来“解决”签名或降级问题。
