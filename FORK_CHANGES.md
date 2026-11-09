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
| `android/patches/apps/discord/patch/src/main/kotlin/app/valenced/patches/discord/Discord.kt` | パッチバンドルが固定する Discord を `352.6 - Alpha` → `352.17 - Stable` | [valenced-extended-manager](https://github.com/uta-a/valenced-extended-manager) はこの版を入れる。VoiceCord の対応表にあり実機で確認済みの版にするため |
| `README.md` | 冒頭に fork の説明・対応/推奨 Discord バージョン・利用上の注意・LSPatch 導入手順、License 節に VoiceCord（GPL-3.0-or-later）と ShadowHook（MIT）を追記 | fork 利用者向けの案内 |

## 新規（上流と衝突しない）

- `android/src/main/kotlin/dev/uta/valencedextended/voicecord/` … ネイティブ部（Kotlin）
- `android/src/main/cpp/voicecord/` … `hook.cpp`・`ring.h`（VoiceCord-mobile `native/src` から移植、JNI シンボルを `dev.uta.valencedextended.voicecord` に変更）、`test/ring_test.cpp`
- `voicecord/signatures/`・`voicecord/re/` … VoiceCord-mobile から移したもの（ビルド対象外）
- `voicecord/optional/` … 旧 `PairingManager.java`・`CommandReceiver.java`（ブロードキャスト操作）。設計書 §10 の任意機能用に保管しているだけで、ビルド対象外（既定オフ）
- `android/src/test/kotlin/dev/uta/valencedextended/voicecord/` … Resampler・SoundboardFetcher のテスト
- `bundle/src/plugins/start/voicecord/` … JS 部（設定画面・サウンドボードの VoiceCord カテゴリ・音源ライブラリ・ネイティブメソッドのラッパ）。サウンドボードへの組み込みは `modules/soundboard/` の `useSoundGrid`・`SoundboardSoundPickerList`・`SoundboardSoundPickerCategories`・`SoundButton`・`SoundboardStore.isPlayingSound` にパッチを当て、描画は Discord の部品に任せる。`manifests.ts` の glob で自動登録されるため上流ファイルの変更は不要
- `.github/workflows/release-extended.yml` … valenced-extended-manager が読むリリース（`app-release.apk`・`valenced.reseam` など）を作る。上流の `release.yml` から types の公開を除き、版番号は CI 内でだけ書き換える（コミットしないので上流の版上げと衝突しない）
- `.github/workflows/upstream-sync.yml` … 毎日上流 `main` を取り込む。衝突しなければ `sync/upstream` でビルド・テストして main への PR を作り、衝突したら `upstream-sync` ラベルの Issue を立てる
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

## GitHub Actions

- 上流のワークフローはファイルを変えずに、リポジトリ側で無効にしている（衝突を避けるため）: `iOS: Build`・`iOS: Deploy Build`・`iOS: Remote IPA Update`（`:ios` を外したため）、`Release`・`API: Publish`（上流の署名鍵・公開先が前提のため）。`Build` は有効
- `Release: Extended`（`release-extended.yml`）を Actions から手動で実行してリリースする。タグは `<上流の版>-ext.<n>`（例 `1.3.0-ext.1`）、versionCode は上流の値に n を足したもの（例 `10301`）。使うシークレットは `KEYSTORE`（base64）・`KEYSTORE_PASSWORD`・`KEY_PASSWORD`・`PATCHES_SIGNING_KEY`（Ed25519 の 32 バイトのシードを base64）、変数は `KEY_ALIAS`・`PATCHES_PUBLIC_KEY`（Manager の `patchesPublicKey` と同じ値。バンドルがこの鍵で署名されたか確かめる）。鍵の原本はリポジトリの外に保管している
- `Upstream: Sync` が PR を作れるよう、Settings > Actions > General の「Allow GitHub Actions to create and approve pull requests」をオンにしている。PR を作れない場合は比較画面へのリンク付きの Issue を立てる。シークレット `SYNC_TOKEN`（Pull requests: write の fine-grained PAT）があればそれで PR を作る
