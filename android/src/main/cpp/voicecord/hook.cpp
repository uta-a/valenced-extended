// libvoicecord.so フェーズ2: 送信 Opus エンコーダ入口の PCM に、デコードした音声ファイルの
// PCM を混ぜて送信する。フェーズ1の固定サイン波→リングバッファ経由の実ファイル再生へ。
//
// 注入点(Krisp 非依存): libdiscord.so 内の WebRtcOpus_Encode（static/stripped, RVA でフック）。
//   int WebRtcOpus_Encode(void* inst, const int16_t* audio_in, size_t samples,
//                         size_t max_bytes, uint8_t* encoded);
//   x1=audio_in が送信 PCM(int16/mono/48kHz), x2=samples(10ms=480 / 20ms=960)。
//
// 手法: shadowhook_intercept_func_addr（pre 割り込み）で cpu_context の x1 を書き換える。
//   hook+CALL_PREV(トランポリンで元プロローグ実行)は signal 7(Bus error)になったため。
//   audio_in は read-only なので、コピー(g_buf)にミックスして x1 を差し替える。
//
// データ経路: Java(CommandReceiver+MediaCodec)→ nativeWrite → リングバッファ(SPSC)
//            → pre_encode(オーディオスレッド)で g_buf に加算 → x1 差し替え。
//
// 送信ゲート: Discord は毎フレーム Connection の ctrl 内の送信デッドラインで
//   送信可否を決める。構造体オフセットは rva.json から渡される。
//   Connection* は Connection::GetStats(約1Hz)の x0 で捕捉し、DeliverRecordedData(約100Hz)を
//   タイマにして再生中だけ ctrl[0] を再アサートする。旧方式(録音バッファへノイズ加算=keepAmp)は廃止。
//
// 実行時 base: エクスポート JNI の実アドレス − 対応表の anchor RVA。
//   RVA と構造体オフセットはモジュール APK 内の rva.json で版別管理する。
#include <jni.h>
#include <android/log.h>
#include <atomic>
#include <cmath>
#include <cstdint>
#include <cstring>
#include <mutex>
#include <pthread.h>
#include <unistd.h>
#include "ring.h"
#include "shadowhook.h"

#define TAG "VoiceCord"
#define LOG(...) __android_log_print(ANDROID_LOG_INFO, TAG, __VA_ARGS__)

static const char *kAnchorSym = "Java_org_webrtc_BuiltinAudioEncoderFactoryFactory_nativeCreateBuiltinAudioEncoderFactory";

// 設定後は不変。release/acquire でインストールスレッドへ公開する。
struct Offsets {
  int version_code;
  uintptr_t anchor;
  uintptr_t encode;
  uintptr_t recorded;
  uintptr_t stats;
  int ctrl;
  int deadline;
};
static Offsets g_offsets{};
static std::atomic<bool> g_offsets_ready{false};
static std::mutex g_offsets_mutex;
// VC切断で Java 側が呼ぶ Connection 破棄 JNI(エクスポート済み=dlsymで解決、版に強い)。
// これを pre で捕まえ、解放前に g_conn を無効化して 100Hz 側の解放済みポインタ触読(UAF)を根絶する。
static const char *kDestroySym = "Java_com_discord_native_engine_NativeConnection_nativeDestroyInstance";
static std::atomic<int> g_version{-1};           // Java から通知される versionCode(-1=未設定)

static std::atomic<bool> g_inject{true};        // リングのミックスを行うか
static std::atomic<int> g_calls{0};
static std::atomic<int> g_scalls{0};
static void *g_stub = nullptr;
static void *g_stub_rec = nullptr;
static void *g_stub_stats = nullptr;
static void *g_stub_destroy = nullptr;
static std::atomic<uintptr_t> g_conn{0};        // 捕捉した Connection*
static std::atomic<int> g_conn_ttl{0};          // 再アサート残フレーム(100Hz基準)。GetStatsで補充
static std::atomic<float> g_gain{1.0f};         // sfx(注入音)の音量
static std::atomic<float> g_duck{1.0f};         // mic(元音声)の音量

// SPSC リングバッファ(約10秒 @48kHz mono)。実装は ring.h。host 単体テストあり。
static Ring g_ring(480000);

// 差し替え用の書込可能バッファ。thread_local により、複数のエンコードスレッドが並行しても
// 各スレッド専用バッファになり競合しない。元 WebRtcOpus_Encode は pre 復帰後 g_buf を同期的に
// 読み切って return する(Opus同期)ため、スタックローカルでは寿命が足りず、per-thread の
// 永続バッファが必要。
// TODO(フェーズ4): uninstall(フック解除)対応。
static thread_local int16_t g_buf[8192];

// NativeConnection_nativeDestroyInstance の pre。VC切断で Connection が破棄される直前に呼ばれる。
// 破棄より前に g_conn/ttl を無効化し、以後の 100Hz 再アサートが解放済みポインタに触れないようにする
// (UAF根絶)。同時に保持する voice Connection は1本のため無条件クリアで実害なし(送信ゲート強制が
// 破棄直前にわずかに早く切れるだけ)。エクスポート済みシンボルを dlsym で解決するため版に強い。
static void pre_destroy(shadowhook_cpu_context_t * /*ctx*/, void * /*data*/) {
  g_conn.store(0, std::memory_order_relaxed);
  g_conn_ttl.store(0, std::memory_order_relaxed);
}

// Connection::GetStats(約1Hz)の pre。x0=Connection* を捕捉して TTL を補充する。
static void pre_getstats(shadowhook_cpu_context_t *ctx, void * /*data*/) {
  uintptr_t conn = (uintptr_t)ctx->regs[0];
  if (conn == 0) return;
  if (g_scalls.load(std::memory_order_relaxed) < 3) {
    LOG("GetStats call#%d conn=%p",
        g_scalls.fetch_add(1, std::memory_order_relaxed), (void *)conn);
  }
  g_conn.store(conn, std::memory_order_relaxed);
  // GetStats は約1Hz発火なので TTL=100(~1s @100Hz)でも接続中は枯れない。UAF窓を小さく保つ。
  g_conn_ttl.store(100, std::memory_order_relaxed);
}

// 録音funnel AudioDeviceBuffer::DeliverRecordedData(~100Hz)の pre 割り込み。
// 約100Hz。捕捉済み Connection* の送信ゲート(ctrl[0])を再生中だけ強制ONにし、
// 停止時は「自分が立てた LLONG_MAX のときだけ」0(=通常VAD)へ戻す。
// TTL: GetStats(約1Hz)が補充。VC切断で GetStats が止まると TTL が枯れ、
//      解放済みかもしれない Connection* への書き込みを止める(安全弁)。
//      ただし GetStats 停止と Connection 解放は非同期なので、TTL 幅(~1s)の
//      UAF 窓は残るが、nativeDestroyInstance(pre_destroy)で破棄直前に g_conn を
//      0 化するため、通常の VC 切断ではこの窓に入らない(TTL は保険として残す)。
// 前提: このフック(録音funnel)は単一スレッドから呼ばれる(pre_encode と同様)。
//      よって g_conn_ttl の load→store(-1) の非アトミック RMW でも取りこぼしなし。
// 既知の制限: PTTモードの端末では復帰時に PTT保持中の LLONG_MAX と区別できない
//      (このデバイスはボイスアクティビティ運用前提)。
static void pre_recorded(shadowhook_cpu_context_t * /*ctx*/, void * /*data*/) {
  uintptr_t conn = g_conn.load(std::memory_order_relaxed);
  int ttl = g_conn_ttl.load(std::memory_order_relaxed);
  if (conn == 0 || ttl <= 0) return;
  g_conn_ttl.store(ttl - 1, std::memory_order_relaxed);
  uintptr_t ctrl = *(uintptr_t *)(conn + g_offsets.ctrl);
  if (ctrl == 0) return;
  volatile int64_t *deadline = (int64_t *)(ctrl + g_offsets.deadline);
  bool playing = g_inject.load(std::memory_order_relaxed) && g_ring.hasData();
  if (playing) {
    *deadline = 0x7fffffffffffffffLL;         // LLONG_MAX: 常時送信
  } else if (*deadline == 0x7fffffffffffffffLL) {
    *deadline = 0;                            // 自分が立てた分だけ通常VADへ復帰
  }
}

// WebRtcOpus_Encode 入口の pre 割り込み。x1=audio_in, x2=samples。
static void pre_encode(shadowhook_cpu_context_t *ctx, void * /*data*/) {
  const int16_t *audio_in = (const int16_t *)ctx->regs[1];
  size_t samples = (size_t)ctx->regs[2];
  if (g_calls.load(std::memory_order_relaxed) < 3) {
    LOG("WebRtcOpus_Encode call#%d samples=%zu",
        g_calls.fetch_add(1, std::memory_order_relaxed), samples);
  }
  if (!g_inject.load(std::memory_order_relaxed)) return;
  if (audio_in == nullptr || samples == 0 || samples > 8192) return;
  if (!g_ring.hasData()) return;  // 再生中でなければ素通し(x1 差し替えなし)
  memcpy(g_buf, audio_in, samples * sizeof(int16_t));  // audio_in は読み取り可
  g_ring.mix(g_buf, samples, g_gain.load(std::memory_order_relaxed),
             g_duck.load(std::memory_order_relaxed));
  ctx->regs[1] = (uint64_t)(uintptr_t)g_buf;  // x1 を書込可能バッファへ差し替え
}

// libdiscord.so のロードを待って base を解決し、WebRtcOpus_Encode を intercept する。
static void *install_thread(void *) {
  if (!g_offsets_ready.load(std::memory_order_acquire)) {
    LOG("RVA 未設定 → フック無効化");
    return nullptr;
  }
  for (int tries = 0; tries < 120; tries++) {  // 最大 ~60s
    void *h = shadowhook_dlopen("libdiscord.so");
    if (h != nullptr) {
      void *anchor = shadowhook_dlsym(h, kAnchorSym);
      void *destroy = shadowhook_dlsym(h, kDestroySym);  // 破棄JNI(dlsym解決=版に強い)
      shadowhook_dlclose(h);
      if (anchor != nullptr) {
        uintptr_t base = (uintptr_t)anchor - g_offsets.anchor;

        // 版ズレガード: versionCode 通知を待ち、対応版でなければフックしない(誤オフセットでのクラッシュ防止)。
        int vc = -1;
        for (int w = 0; w < 120; w++) {            // 最大 ~60s 待つ
          vc = g_version.load(std::memory_order_relaxed);
          if (vc >= 0) break;
          usleep(500 * 1000);
        }
        if (vc != g_offsets.version_code) {
          LOG("版ズレ: expected=%d actual=%d → フック無効化", g_offsets.version_code, vc);
          return nullptr;
        }
        LOG("版一致: versionCode=%d", vc);

        void *target = (void *)(base + g_offsets.encode);
        LOG("libdiscord base=%p target(WebRtcOpus_Encode)=%p", (void *)base, target);
        g_stub = shadowhook_intercept_func_addr(target, pre_encode, nullptr,
                                                SHADOWHOOK_INTERCEPT_DEFAULT);
        int e = shadowhook_get_errno();
        LOG("intercept WebRtcOpus_Encode stub=%p errno=%d (%s)", g_stub, e, shadowhook_to_errmsg(e));

        void *rtarget = (void *)(base + g_offsets.recorded);
        g_stub_rec = shadowhook_intercept_func_addr(rtarget, pre_recorded, nullptr,
                                                    SHADOWHOOK_INTERCEPT_DEFAULT);
        LOG("intercept DeliverRecordedData stub=%p errno=%d (%s)", g_stub_rec,
            shadowhook_get_errno(), shadowhook_to_errmsg(shadowhook_get_errno()));

        void *starget = (void *)(base + g_offsets.stats);
        g_stub_stats = shadowhook_intercept_func_addr(starget, pre_getstats, nullptr,
                                                      SHADOWHOOK_INTERCEPT_DEFAULT);
        LOG("intercept GetStats stub=%p errno=%d (%s)", g_stub_stats,
            shadowhook_get_errno(), shadowhook_to_errmsg(shadowhook_get_errno()));

        // UAF根絶: Connection 破棄 JNI を pre で捕まえて g_conn を無効化する。
        if (destroy != nullptr) {
          g_stub_destroy = shadowhook_intercept_func_addr(destroy, pre_destroy, nullptr,
                                                          SHADOWHOOK_INTERCEPT_DEFAULT);
          LOG("intercept nativeDestroyInstance stub=%p errno=%d (%s)", g_stub_destroy,
              shadowhook_get_errno(), shadowhook_to_errmsg(shadowhook_get_errno()));
        } else {
          LOG("nativeDestroyInstance 未解決(UAF根絶フックはスキップ)");
        }
        return nullptr;
      }
      LOG("anchor sym 未解決。リトライ");
    }
    usleep(500 * 1000);
  }
  LOG("libdiscord.so を解決できず(タイムアウト)");
  return nullptr;
}

// Kotlin で検証した対応表を一度だけ受け取る。稼働中のオフセット変更は許可しない。
extern "C" JNIEXPORT jboolean JNICALL
Java_dev_uta_valencedextended_voicecord_NativeBridge_nativeSetOffsets(
    JNIEnv *, jclass, jint version, jlong anchor, jlong encode, jlong recorded,
    jlong stats, jint ctrl, jint deadline) {
  if (version <= 0 || anchor <= 0 || encode <= 0 || recorded <= 0 ||
      stats <= 0 || ctrl < 0 || deadline < 0) return JNI_FALSE;
  std::lock_guard<std::mutex> lock(g_offsets_mutex);
  if (g_offsets_ready.load(std::memory_order_acquire)) {
    return g_offsets.version_code == version &&
           g_offsets.anchor == (uintptr_t)anchor &&
           g_offsets.encode == (uintptr_t)encode &&
           g_offsets.recorded == (uintptr_t)recorded &&
           g_offsets.stats == (uintptr_t)stats &&
           g_offsets.ctrl == ctrl && g_offsets.deadline == deadline
               ? JNI_TRUE : JNI_FALSE;
  }
  g_offsets = {version, (uintptr_t)anchor, (uintptr_t)encode, (uintptr_t)recorded,
               (uintptr_t)stats, ctrl, deadline};
  g_offsets_ready.store(true, std::memory_order_release);
  return JNI_TRUE;
}

extern "C" JNIEXPORT jint JNICALL
Java_dev_uta_valencedextended_voicecord_NativeBridge_nativeInit(JNIEnv *, jclass) {
  LOG("nativeInit: shadowhook version=%s", shadowhook_get_version());
  pthread_t t;
  if (pthread_create(&t, nullptr, install_thread, nullptr) != 0) {
    LOG("pthread_create 失敗");
    return -1;
  }
  pthread_detach(t);
  return 0;
}

// Java から Discord の versionCode を通知する。対応版判定は install_thread の版ズレガードで行う。
extern "C" JNIEXPORT void JNICALL
Java_dev_uta_valencedextended_voicecord_NativeBridge_nativeSetVersion(JNIEnv *, jclass, jint code) {
  g_version.store(code, std::memory_order_relaxed);
  LOG("versionCode set=%d", (int)code);
}

extern "C" JNIEXPORT void JNICALL
Java_dev_uta_valencedextended_voicecord_NativeBridge_nativeSetInject(JNIEnv *, jclass, jboolean on) {
  g_inject.store(on, std::memory_order_relaxed);
  LOG("inject=%d", (int)on);
}

// デコードスレッドから 48kHz/int16/mono の PCM を投入する。pcm[off, off+len) を書き、書けたサンプル数を返す。
extern "C" JNIEXPORT jint JNICALL
Java_dev_uta_valencedextended_voicecord_NativeBridge_nativeWrite(JNIEnv *env, jclass, jshortArray pcm, jint off, jint len) {
  if (pcm == nullptr || off < 0 || len <= 0) return 0;
  jsize alen = env->GetArrayLength(pcm);
  if (off >= alen) return 0;
  if (len > alen - off) len = alen - off;  // 申告 len が配列長を超えても範囲外読みしない
  jshort *p = env->GetShortArrayElements(pcm, nullptr);
  if (p == nullptr) return 0;
  size_t w = g_ring.write((const int16_t *)(p + off), (size_t)len);
  env->ReleaseShortArrayElements(pcm, p, JNI_ABORT);  // 変更しないので破棄
  return (jint)w;
}

// 再生停止: 現在の tail までを drain 要求する(g_head は consumer 単独更新のため直接触らない)。
extern "C" JNIEXPORT void JNICALL
Java_dev_uta_valencedextended_voicecord_NativeBridge_nativeStop(JNIEnv *, jclass) {
  g_ring.requestStop();
  LOG("stop");
}

extern "C" JNIEXPORT void JNICALL
Java_dev_uta_valencedextended_voicecord_NativeBridge_nativeSetParams(JNIEnv *, jclass, jfloat gain, jfloat duck) {
  g_gain.store(gain, std::memory_order_relaxed);
  g_duck.store(duck, std::memory_order_relaxed);
  LOG("params gain=%.2f duck=%.2f", gain, duck);
}

// bit0=hooked, bit2=playing(リングにデータあり)。
extern "C" JNIEXPORT jint JNICALL
Java_dev_uta_valencedextended_voicecord_NativeBridge_nativeState(JNIEnv *, jclass) {
  int s = 0;
  if (g_stub != nullptr) s |= 1;
  if (g_ring.hasData()) s |= 4;
  return s;
}
