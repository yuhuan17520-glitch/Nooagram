# Nooagram

Nooagram 是基于 [Nagram XF](https://github.com/Keeperorowner/NagramXF) 的 Telegram Android 客户端。它保留 Nagram XF / Nagram X 的主体功能与 AyuMoments 体验，同时加入 Nooagram 自己的更新和快捷功能。

## 主要功能

- **一键屏蔽消息**：长按消息或进入多选模式后，可将消息关键词加入全局过滤规则，不需要先复制文案再进设置。
- **隐藏指定群置顶**：可在聊天页右上角菜单中直接隐藏或恢复当前群的置顶。
- **自定义存储路径开关**：提供独立的存储路径开关，开启后手动点击下载的文件自动保存至公共下载目录，且杜绝自动生成空文件夹。

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

Nooagram automatically rebases onto the latest release tag of [Nagram XF](https://github.com/Keeperorowner/NagramXF). If the rebase conflicts, GitHub opens an issue instead of using unstable/unpublished commits.

## Versioning

Nooagram follows Semantic Versioning 2.0.0. The version name uses the format:

```text
1.0.0-<NagramXF version>.<NagramXF release tag>
```

For example:

```text
1.0.1-12.10.1.1450
```

## Acknowledgments

- [Nagram XF](https://github.com/Keeperorowner/NagramXF)
- [Nagram X](https://github.com/risin42/NagramX)
- [AyuGram](https://github.com/AyuGram/AyuGram4A)
- [Cherrygram](https://github.com/arsLan4k1390/Cherrygram)
- [exteraGram](https://github.com/exteraSquad/exteraGram)
- [OctoGram](https://github.com/OctoGramApp/OctoGram)
