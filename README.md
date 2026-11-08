<p align="center">
    <img
        width="128px"
        src="assets/logo.svg"
    >
    <h1 align="center">Valenced</h1>
    <p align="center"><strong>Supercharge your Discord experience.</strong> A client modification for Discord mobile.</p>
    <br>
    <div align="center">
        <a href="https://discord.com/invite/dtBTQHYSx4">
            <picture>
                <source height="32px" media="(prefers-color-scheme: dark)" srcset="https://user-images.githubusercontent.com/13122796/178032563-d4e084b7-244e-4358-af50-26bde6dd4996.png" />
                <img height="32px" src="https://user-images.githubusercontent.com/13122796/178032563-d4e084b7-244e-4358-af50-26bde6dd4996.png" />
            </picture>
        </a>&nbsp;&nbsp;&nbsp;&nbsp;&nbsp;&nbsp;
        <a href="https://github.com/Valenced">
            <picture>
                <source height="32px" media="(prefers-color-scheme: dark)" srcset="https://i.ibb.co/dMMmCrW/Git-Hub-Mark.png" />
                <img height="32px" src="https://i.ibb.co/9wV3HGF/Git-Hub-Mark-Light.png" />
            </picture>
        </a>
    </div>
</p>

> [!NOTE]
> **valenced-extended** は Valenced の fork です。送信音声に任意の音源（ファイル・Discord サウンドボード）をミックスする **VoiceCord** を内部プラグインとして同梱しています。上流ファイルへの変更は [FORK_CHANGES.md](FORK_CHANGES.md) にまとめています。
>
> - 対象: Discord Android（arm64-v8a）。iOS は対象外
> - VoiceCord は `libdiscord.so` の非エクスポート関数を RVA で直接フックするため、対応表（[voicecord/signatures/rva.json](voicecord/signatures/rva.json)）にある Discord のバージョンでしか動きません。非対応版では VoiceCord だけが止まり、Valenced の他の機能は動きます
> - **推奨 Discord バージョン**: 352.17（versionCode 352017）。実機（Android 13）で、相手側に音が届くことを確認済みです。350.8（350008）と 345.9（345009）も対応表にありますが、350.8 は静的解析で求めた値のみで実機では未確認です
> - **利用上の注意**: クライアント改変は Discord の利用規約上推奨されません。サブアカウントで、同意のある相手との検証にだけ使ってください。パッチ済みの Discord APK は配布しません

### 導入（LSPatch・非 root）

1. モジュール APK をビルドする（手順は [FORK_CHANGES.md](FORK_CHANGES.md) の「ビルド環境」）。出力は `android/build/outputs/apk/{debug,release}/`
2. 公式 Discord をアンインストールする（LSPatch は再署名するので公式版と共存できない）: `adb uninstall com.discord`
3. 対応版の Discord 分割 APK（`base.apk`・`split_config.arm64_v8a.apk` など）を自分の端末や正規の入手元から用意する
4. LSPatch（JingMatrix fork の `lspatch.jar`）でモジュールを埋め込む:

   ```sh
   java -jar lspatch.jar base.apk split_config.arm64_v8a.apk split_config.*.apk \
     -m android-release.apk -l 2 -d -f -o out
   adb install-multiple -r out/*.apk
   ```

5. Discord を起動し、Valenced の設定 > プラグイン > VoiceCord で対応状況（フック済み・対応版か）を確認する
6. 同じ設定画面で音源ファイルを追加する。VC 中にサウンドボードを開くと、Discord の音と同じ形の「VoiceCord」カテゴリから再生できる。再生中の音をもう一度押すと止まる

root＋Xposed（Vector など）の場合は、モジュール APK をインストールして `com.discord` をスコープに入れる。

## ❓ About

Valenced is a client modification for Discord mobile. This repository is a monorepo that builds Valenced.

## ✨ Features

- **🔌 Plugins**: Extend Discord with custom features
- **🎨 Themes, fonts, icons & emojis**: Customize Discord's appearance to your liking
- **🧪 Experiments**: Try out Discord's new features before they're rolled out

## 👷 Codebase

Valenced uses TypeScript and Kotlin Multiplatform to share code between platforms. The codebase is split into four modules:

- **[bundle](bundle)**: Hermes bytecode that runs inside official Discord clients. It requires the loaders to run, and is built into them in each release.
- **[core](core)**: Shared Kotlin Multiplatform codebase for the loaders, containing the plugin system, and other shared code.
- **[android](android)**: Android loader and native plugin API, which is an Xposed module loading the Valenced bundle into Discord.
  - **[android/patches](android/patches)**: [Reseam](https://reseam.app) patches Valenced Manager applies to Discord to load the Xposed module.
- **[ios](ios)**: iOS loader, which is a tweak loading the Valenced bundle into Discord.

## 📄 License

This project is licensed under the GPL-3.0 License. See [LICENSE](LICENSE) for details.

[android/patches](android/patches) is licensed under the AGPL-3.0, see [its license](android/patches/LICENSE) and [notice](android/patches/NOTICE).

VoiceCord のコード（`android/src/main/kotlin/dev/uta/valencedextended/`、`android/src/main/cpp/voicecord/`、`bundle/src/plugins/start/voicecord/`、`voicecord/`）は GPL-3.0-or-later です。同梱の [ShadowHook](https://github.com/bytedance/android-inline-hook)（bytedance/android-inline-hook）は MIT License です。
