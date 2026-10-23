# Nooagram 1.3.0 正式签名候选包验证

日期：2026-10-23。**尚未公开发布；已完成真实生产覆盖升级，migration_ready=true 允许最终 release 构建。公开前仍须安装和复测最终 APK。**

## 实验性功能页重置设置修复

- 真机复测发现打开上游实验性功能页时，隐藏功能的旧逻辑会写入关闭值，影响 AyuMoments 已保存的消息过滤、本地大会员、去广告等设置及幽灵模式。已停止旧最终构建 39936602011，未公开该包。
- 删除页面可见性计算中的设置写入，仅保留行显隐规则，不改变隐藏功能解锁条件。新增探针从当前生产源码提取实际方法，验证隐藏/显示两种状态及重复重建时不写设置或幽灵模式。修复前 25 通过、1 失败，修复后 26 通过、0 失败。
- 此修复后的正式 APK 仍须重建并真机复测；以下旧候选包验证不是最终包验证。

## 最新真机结果（替代下文早期“待连接/未升级”状态）

- Android 17 / ARM64 手机旧生产版为 1.2.3、versionCode 125000081。先通过旧应用导出数据库和设置至手机 Downloads 及本机独立证据目录，设置备份排除 API keys，未发送至聊天。这不是完整登录凭据/秘密聊天回滚备份。
- 正式签名 ARM64 候选包通过 adb install -r 覆盖成功，没有卸载或清除数据。UID、首次安装时间不变，登录账号、聊天文件夹和中文设置保留。
- 三次冷启动后十秒仍在前台，原生签名一致，未发现该次进程的 FATAL/JNI/Room 校验错误。TotalTime 为 832、846、873ms，只代表启动时间。
- 通过真实聊天 AyuGram 菜单打开删除归档；当前屏幕可匹配旧库 17 条文字记录。另一个含 10 条旧媒体记录的会话正常显示媒体气泡和元数据，未据此宣称所有媒体可下载或播放。
- 新版导出库 integrity_check=ok、schema 35、Room identity 不变。按全部列值含 BLOB 逐行比较，升级前所有旧行保留：删除消息 183→262、反应 77→78、删除对话 1→1、正则规则 57→57，旧行缺失/变化全部为 0。在线期间新增事件解释记录增长。编辑表原为 0，不能声称真实编辑归档已验证。
- 正式候选发现导入/导出导航行误显示开关，使防误触逻辑挡住文字点击。afb3f75adaa0765819b4959b621d090ef2d2fb62 修复 TextCell 三个图标导航 setter，隐藏构造或复用遗留的开关；新增回归修复前失败、修复后通过。
- 独立 debug 真机包验证导出文字打开系统保存窗口、开关文字点击前后偏好哈希不变、长按开关正常弹菜单，没有复现类型转换崩溃。最终正式 APK 必须重复这些检查。
- [精确修复源码 CI 39933896343](https://github.com/yuhuan17520-glitch/Nooagram/actions/runs/39933896343)成功：126 项选定单测、0 失败、0 跳过，其中迁移 116 项、CarVoiceAttachment 10 项。Release R8/优化后 DEX 契约检查、30 预览探针、隐私历史 10 用例 302 断言、媒体 3 项、置顶/设置 25 项、上游提案 11 场景通过。
- 原来两项 CarVoiceAttachment Windows 路径失败在 Linux 十项测试中通过；其余五项 JNI/NaConfig/OpenCC/DNS 测试环境初始化失败未改称通过。不是全仓库测试通过声明。
- ARMv7 未实机运行，多账号通知、退出重登、所有选项、断网转发、全部媒体播放、长期稳定性没有逐项真机覆盖。先前无新帧的 gfxinfo 采样不用于流畅度结论。用户已接受先前测试版，本轮补充正式升级和真实归档验证。
- 正式流水线仅生成私有草稿；不把 candidate 改名发布。最终 APK 核验、覆盖安装、导航/启动复测后才公开。自动上游合并及自动公开发布保持关闭。

本机证据：任务 outputs/Nooagram-Nagram-release-validation，包括 phone-upgrade-row-preservation.json、phone-after-backup-verification.json、归档截图、冷启动日志、CI 报告。真实账号、正文、备份及设备标识不提交仓库。

以下保留早期验证经过；其中待连接、未覆盖和 migration_ready=false 为当时状态，以本节为准。

## 可追溯构建

- 构建源码：`2e747e10c05045d5e86a4ba1fe91ebc8e18e85cc`。
- 固定上游：NextAlone/Nagram `87863c8a57854c739e1452e56591c07ea52415d3`，Nagram 1255 / Telegram 13.0.2。
- [签名候选构建 39903707486](https://github.com/yuhuan17520-glitch/Nooagram/actions/runs/39903707486)：三个架构全部成功。
- versionName：`1.3.0-13.0.2.n1255+125001000`；versionCode：`125001000`。
- 包名：`fork.yuhuan.nooagram`，非 debuggable，启用 R8 和资源收缩。
- CI 迁移范围单元测试：115 项，0 失败、0 跳过；不是全仓库测试声明。
- 三个架构均通过优化后 DEX 的 Room/LockedDao 契约检查。
- 签名只使用 GitHub 已有受保护 secrets；本机正式密钥未读取或复制。

## 安装包核验

三个 APK 均验证通过 APK 签名，证书与上个公开正式版一致：

```text
SHA256 A6FF043A4808DC097B9C62CB3939536A055B4567441A7EA61A78B443640F8088
SHA1   A112541EF0BDCF65D78004DF6CACE9C85F7D65DD
```

原生库从固定源码构建，绑定同一 Nooagram 公开证书，未混入上游证书绑定库。

| ABI | APK 字节数 | APK SHA256 |
| --- | ---: | --- |
| arm64-v8a | 49052134 | `5a1151152b1a39860793f78923a04cc2fa23e7517649d412bb75f517a1d52166` |
| armeabi-v7a | 47257958 | `902c4f953df2356f4c8f5e52907a0bdcb309d87c14e94b9836d964a9489208d8` |
| x86_64 | 49698613 | `a2634505c811a650f615f68747e85c5f8eaa019076ae98e89234d98860bc3ea0` |

## 隔离模拟器覆盖升级

仅使用新建 AVD `Nooagram_Upgrade_QA_API36`（API 36，x86_64）。没有卸载、清除或覆盖手机正式版。

1. 安装 GitHub 上实际发布的旧版 `v1.2.3-12.10.1.1251+125000081` x86_64 APK，启动至欢迎页，不登录。
2. 在该隔离安装中注入合成 schema-35 `ayu-data`：一条删除归档、一条编辑归档、二进制占位 BLOB、一条正则规则；另保存 `DisableAds=true` 及合成偏好标记。不是用户数据。
3. `adb -s emulator-5554 install -r` 新版 x86_64 候选包成功。UID 保持 10216，firstInstallTime 未变，versionCode 从 125000081 增至 125001000。
4. 三次冷启动后十秒仍在前台，PID 分别 5081、5200、5315。每次观察到原生“签名一致”，未发现该次进程的 FATAL/JNI 错误。截图及 hierarchy 为正常欢迎页。
5. 升级后数据库 integrity_check 为 ok；schema 35、Room identity 不变；三张合成数据表全部列值（含 BLOB）逐项一致。两个偏好标记保留。
6. 同模拟器使用 `--abi arm64-v8a -r` 换装 ARM64 包，确认 primaryCpuAbi=arm64-v8a。通过系统 libndk_translation 运行，三次冷启动（PID 5660、5776、5896）同样通过。**这是 ARM64 转译测试，不是 ARM64 真机测试。**

合成数据工具：`Tools/create-upgrade-fixture.py`、`Tools/verify-upgrade-fixture.py`。本机证据保存于任务 outputs/Nooagram-Nagram-release-validation，不提交截图、设备日志或任何真实账号数据。

## 明确未验证与发布闸门

- 欢迎页未登录；文件内容保留不等于实际签名包已经通过 Room DAO 打开旧库、真实 TL 反序列化、归档展示或登录会话迁移。Room/schema/LockedDao 的执行验证目前来自单元测试及优化后 DEX 检查。
- ARMv7 仅有构建及静态核验，没有运行设备。
- 正式签名 ARM64 真机启动、真实旧版数据覆盖升级尚未完成。本轮手机最初可见，随后从 ADB 断开；没有对手机执行正式包安装。等待重新连接后，在可恢复备份前不做破坏性操作。
- 先前 debug 包的用户测试不能直接替代正式签名、R8 优化后的运行测试。
- `docs/nagram-migration.md` 中未完成项和 7 项全仓库测试失败仍需按各自边界处理，不能改称全套通过。
- 未创建正式 release、未更新 latest/update.json、未改变默认 ayu 分支，未启用自动上游合并或自动发布。

只有完成必要验证并审核剩余风险后才更新 migration_ready 并公开发布。不要为绕过闸门临时设为 true；不要用卸载正式版来解决升级失败。

## 后续真机连接及发布流程准备

- 前一部手机已确认旧正式版仍登录，但系统日志明确报告 Input event injection failed，未完成导出备份或安装。随后该设备断开。
- 新连接设备 PMA110 / Android 17 安装的仍为旧正式版 125000081。系统目前因 1% 低电量进入应急保护模式，拒绝启动应用；等待充电后继续。本机没有覆盖该设备正式版，也没有导出其数据。
- 新增正式 release 变体的三架构构建流程，以及更新清单、校验和和版本闸门的隔离测试。流程仅生成私有草稿，公开发布前仍需安装并测试实际最终 APK。真实 metadata 的 migration_ready 保持 false。
- CarVoiceAttachmentTest 的两项 Windows 失败已获得明确 FileProvider 异常：配置根目录无法匹配 Windows 缓存路径。AndroidX core 1.16.0 的 belongsToRoot 使用硬编码斜杠拼接根目录，而 Windows canonical path 使用反斜杠。加入直接断言以显露异常，不修改生产 FileProvider。Linux CI 加入该组十项测试；结果待验证，不能据此宣称通过。其余五项原生环境失败也未改称通过。
