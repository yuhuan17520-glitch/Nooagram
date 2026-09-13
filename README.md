# Nooagram

Nooagram 是基于 [Nagram XF](https://github.com/Keeperorowner/NagramXF) 的 Telegram Android 客户端。它保留 Nagram XF / Nagram X 的主体功能与 AyuMoments 体验，同时加入 Nooagram 自己的更新和快捷功能。

## 主要功能

- **应用内更新**：更新清单托管在本仓库 GitHub Releases，可在应用内直接检查并下载。
- **一键屏蔽消息**：长按消息或进入多选模式后，可将消息关键词加入全局过滤规则，不需要先复制文案再进设置。
- **隐藏指定群置顶**：可在聊天页右上角菜单中直接隐藏或恢复当前群的置顶。
- **高刷新率列表**：提供可开关的高刷请求，默认关闭；不影响系统省电策略。
- **独立包名**：`fork.yuhuan.nooagram`，可与 Nagram XF / Nagram X 并存。
- **自动构建**：推送代码后自动生成 arm64-v8a 与 armeabi-v7a APK，并同步应用内更新清单。

## 下载

- [GitHub Releases](https://github.com/yuhuan17520-glitch/Nooagram/releases)
- [应用内更新清单](https://github.com/yuhuan17520-glitch/Nooagram/releases/latest/download/update.json)

大多数现代 Android 手机选择 `arm64-v8a`；32 位设备选择 `armeabi-v7a`。

## 赞助

Nooagram 目前没有自己的赞助渠道。如果你想支持上游原作者，请通过 Nagram XF 的赞助页面赞助：

- [Nagram XF 原项目赞助说明](https://github.com/Keeperorowner/NagramXF#sponsor)
- [爱发电（Nagram XF 原作者）](https://ifdian.net/a/nagramxf)
- [Ko-fi（Nagram XF 原作者）](https://ko-fi.com/nagramxf)

## 编译

1. Clone the repository with submodules:

    ```bash
    git clone --recursive --shallow-submodules https://github.com/yuhuan17520-glitch/Nooagram.git Nooagram
    cd Nooagram
    git submodule update --init --recursive --depth=1
    ```

2. Create `local.properties` in the repository root:

    ```properties
    TELEGRAM_APP_ID=<your_telegram_app_id>
    TELEGRAM_APP_HASH=<your_telegram_app_hash>
    KEYSTORE_PASS=<your_keystore_password>
    ALIAS_NAME=<your_alias_name>
    ALIAS_PASS=<your_alias_password>
    ```

3. Replace signing and service files with your own:

    - `TMessagesProj/release.keystore`
    - `TMessagesProj/google-services.json`
    - Google Maps API key in `TMessagesProj/src/main/AndroidManifest.xml`

4. Open the project in Android Studio, or build with:

    ```bash
    ./gradlew TMessagesProj:assembleNormalRelease
    ```

GitHub Actions uses an external signing key and does not commit private credentials.

## Upstream

Nooagram automatically merges the `dev` branch of [Nagram XF](https://github.com/Keeperorowner/NagramXF). If the merge conflicts, GitHub opens an issue instead of silently overwriting changes.

## Acknowledgments

- [Nagram XF](https://github.com/Keeperorowner/NagramXF)
- [Nagram X](https://github.com/risin42/NagramX)
- [AyuGram](https://github.com/AyuGram/AyuGram4A)
- [Cherrygram](https://github.com/arsLan4k1390/Cherrygram)
- [exteraGram](https://github.com/exteraSquad/exteraGram)
- [OctoGram](https://github.com/OctoGramApp/OctoGram)
