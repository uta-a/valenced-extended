# FORK_CHANGES

valenced-extended が上流 [Valenced/valenced](https://github.com/Valenced/valenced) のファイルに加えた変更の一覧。
上流をマージするときは、ここに挙げたファイルの衝突を確認する。新規ディレクトリ（VoiceCord のコード）はここには載せない。

基点: `upstream/main` 0f9c6d9

| ファイル | 変更 | 理由 |
| --- | --- | --- |
| `settings.gradle.kts` | `include(":ios")` を削除 | iOS は対象外 |
| `android/build.gradle.kts` | `applicationId` を `app.valenced.xposed` → `dev.uta.valencedextended` | 上流と別のモジュールとして区別するため。`namespace`・Kotlin パッケージ・`xposed_init`・データディレクトリ `files/valenced` は上流のまま |
| `android/build.gradle.kts` | `externalNativeBuild`（CMake 3.22.1, `src/main/cpp/voicecord/CMakeLists.txt`）、`abiFilters += "arm64-v8a"`、`prefab = true`、`packaging.jniLibs.pickFirsts += "**/libshadowhook.so"`、`implementation(libs.shadowhook)` | VoiceCord ネイティブ部（`libvoicecord.so`）と ShadowHook 2.0.1 |
| `android/build.gradle.kts` | `BundleAssets` に `voicecordRva` 入力（`@InputFile`）を追加し、`../voicecord/signatures/rva.json` を APK の `assets/voicecord/rva.json` へコピー（各 variant で設定） | VoiceCord の RVA 対応表を実行時に読むため（M4） |
| `gradle/libs.versions.toml` | `shadowhook = "2.0.1"` と `com.bytedance.android:shadowhook` を追加 | 同上 |
| `android/src/main/kotlin/app/valenced/xposed/tweaks/plugins/internal/InternalPluginRegistry.kt` | `internalPlugins` に `voicecordPlugin` を追加（import 1 行＋リスト 1 項目） | VoiceCord を内部ネイティブプラグインとして登録 |
| `README.md` | 冒頭に fork の説明・対応/推奨 Discord バージョン・利用上の注意・LSPatch 導入手順、License 節に VoiceCord（GPL-3.0-or-later）と ShadowHook（MIT）を追記 | fork 利用者向けの案内 |

## 新規（上流と衝突しない）

- `android/src/main/kotlin/dev/uta/valencedextended/voicecord/` … ネイティブ部（Kotlin）
- `android/src/main/cpp/voicecord/` … `hook.cpp`・`ring.h`（VoiceCord-mobile `native/src` から移植、JNI シンボルを `dev.uta.valencedextended.voicecord` に変更）、`test/ring_test.cpp`
- `voicecord/signatures/`・`voicecord/re/` … VoiceCord-mobile から移したもの（ビルド対象外）
- `voicecord/optional/` … 旧 `PairingManager.java`・`CommandReceiver.java`（ブロードキャスト操作）。設計書 §10 の任意機能用に保管しているだけで、ビルド対象外（既定オフ）
- `android/src/test/kotlin/dev/uta/valencedextended/voicecord/` … Resampler・SoundboardFetcher のテスト
- `bundle/src/plugins/start/voicecord/` … JS 部（設定画面・サウンドボードの VoiceCord 欄・音源ライブラリ・ネイティブメソッドのラッパ）。サウンドボードへの組み込みは `modules/soundboard/native/SoundboardSoundPicker.tsx` の描画結果にパッチを当てる。`manifests.ts` の glob で自動登録されるため上流ファイルの変更は不要
- `voicecord/re/arm64_static.py` … 350.8・352.17 の RVA 導出に使った静的解析スクリプト（ビルド対象外）
- `voicecord/re/match_version_patterns.py` … 旧版の関数先頭パターンを新版の `libdiscord.so` から探すスクリプト（352.17 の候補探索に使用、ビルド対象外）

## 対応版の確認状況

| Discord | versionCode | RVA の根拠 | 実機 |
| --- | --- | --- | --- |
| 352.17 | 352017 | 静的解析 | 確認済み（2026-11-09、Android 13、LSPatch。相手側で音を確認） |
| 350.8 | 350008 | 静的解析 | 未確認 |
| 345.9 | 345009 | VoiceCord-mobile から移植 | 未確認（この fork では） |

## ビルド環境

- JDK 25（`jvmToolchain(25)`）: `brew install openjdk@25`
- Deno 2.9.7（`bundle/.dvmrc` で固定）
- 例:

```sh
export JAVA_HOME=/opt/homebrew/opt/openjdk@25/libexec/openjdk.jdk/Contents/Home
export PATH="$HOME/.deno/bin:$JAVA_HOME/bin:$PATH"
./gradlew :android:assembleDebug   # → android/build/outputs/apk/debug/android-debug.apk
```

`:ios` を外しても、macOS では `core` の iOS ターゲット（`appleTargets`）は残る。`:android:assembleDebug` では iOS のタスクは走らない。
