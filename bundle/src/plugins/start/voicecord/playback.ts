import * as Native from './native'
import type { Sound, Storage } from './library'
import type { PlaybackResult } from './native'

// どの画面から再生しても、現在の再生要求と音源ごとの音量倍率はここで一元管理する。

export const clampVolume = (value: number) =>
    Number.isFinite(value) ? Math.min(2, Math.max(0, Math.round(value * 10) / 10)) : 1

interface Request {
    id: number
    /** ライブラリの音源 ID。テスト音・ID 指定のサウンドボードは undefined。 */
    soundId: string | undefined
    volume: number
    pending: boolean
    failed: boolean
    /** 要求の後に再生状態の通知を受けたか。受けずに再生が始まった場合（再生し直し）を判定する。 */
    notified: boolean
    awaitingState: boolean
    /** 自分向けに端末で鳴らしている再生の番号。鳴らしていなければ undefined。 */
    localId: number | undefined
}

// 端末だけで鳴らす再生（プレビュー・自分向け）。同時に1つで、新しく鳴らした側が持ち主になる。
let localId = 0

/** 端末だけで鳴らし、その再生の番号を返す。音量は 1 を超えられないので切り詰める。 */
export function playLocal(uri: string, volume: number, onError?: (error: unknown) => void) {
    const id = ++localId
    const fail = (error: unknown) => { if (id === localId) onError?.(error) }
    try {
        Native.previewUri(uri, Math.min(1, volume)).then(result => {
            if (!result?.ok) fail(result?.error)
        }, fail)
    } catch (e) { fail(e) }
    return id
}

/** id の再生がまだ鳴っていれば音量を変える。 */
export function setLocalVolume(id: number | undefined, volume: number) {
    if (id === undefined || id !== localId) return
    Native.setPreviewVolume(Math.min(1, volume))
}

/** id の再生がまだ鳴っていれば止める。id を省くと何が鳴っていても止める。 */
export function stopLocal(id?: number) {
    if (id !== undefined && id !== localId) return
    localId++
    Native.stopPreview()
}

let latest: Request | undefined
let nextId = 0
let nativePlaying = false
const listeners = new Set<() => void>()

function emit() {
    for (const listener of listeners) {
        try { listener() } catch {}
    }
}

const isActive = (request: Request | undefined): request is Request =>
    !!request && !request.failed && (request.pending || request.awaitingState || nativePlaying)

/** 再生中のライブラリ音源 ID。再生していない、またはライブラリ外の音なら undefined。 */
export const currentSoundId = () => (isActive(latest) ? latest.soundId : undefined)

export function onPlaybackChange(listener: () => void) {
    listeners.add(listener)
    return () => { listeners.delete(listener) }
}

/** ネイティブの再生状態をつなぐ。プラグインの start で一度呼び、戻り値で解除する。 */
export function watchNativeState() {
    const remove = Native.onState(({ playing }) => {
        nativePlaying = playing
        if (latest) {
            latest.notified = true
            latest.awaitingState = false
        }
        emit()
    })
    return () => {
        remove()
        latest = undefined
        nativePlaying = false
        emit()
    }
}

/** 全体の音量に、再生中の音源の倍率を掛けてネイティブへ渡す。積は丸めない。 */
export function applyParams(settings: Storage, soundVolume = isActive(latest) ? latest.volume : 1) {
    Native.setParams(clampVolume(settings.gain ?? 1) * soundVolume, clampVolume(settings.duck ?? 1))
}

async function start(settings: Storage, soundId: string | undefined, volume: number,
    run: () => PlaybackResult | Promise<PlaybackResult>): Promise<PlaybackResult> {
    const request: Request = {
        id: ++nextId, soundId, volume, pending: true, failed: false, notified: false, awaitingState: false,
        localId: undefined,
    }
    latest = request
    emit()
    const settle = (ok: boolean) => {
        if (latest !== request) return
        request.pending = false
        request.failed = !ok
        // 再生し直しでは状態が true のまま変わらず通知が来ないので、終了の通知まで再生中とみなす
        request.awaitingState = ok && !request.notified
        if (!ok) stopLocal(request.localId)
        emit()
    }
    try {
        applyParams(settings, volume)
        const result = await run()
        settle(!!result?.ok)
        return result
    } catch (e) {
        settle(false)
        throw e
    }
}

export function playSound(settings: Storage, sound: Sound) {
    const volume = clampVolume(sound.volume ?? 1)
    return start(settings, sound.id, volume, () => {
        // 自分にも聞こえるようにする設定（既定はオン）。送信と同時に端末でも鳴らす。
        if (settings.monitor ?? true) {
            const request = latest
            const id = playLocal(sound.uri, clampVolume(settings.gain ?? 1) * volume)
            if (request) request.localId = id
        }
        return Native.playUri(sound.uri)
    })
}

/** 送信も自分向けの再生も止める。 */
export function stopPlayback() {
    stopLocal()
    Native.stop()
}

/** ライブラリ外の音（テスト音・ID 指定のサウンドボード）を等倍で再生する。 */
export const playOther = (settings: Storage, run: () => PlaybackResult | Promise<PlaybackResult>) =>
    start(settings, undefined, 1, run)

/** 再生中の音源の倍率を変える。その音源が再生中でなければ何もしない。 */
export function setLiveSoundVolume(settings: Storage, soundId: string, volume: number) {
    if (currentSoundId() !== soundId || !latest) return
    latest.volume = volume
    applyParams(settings, volume)
    setLocalVolume(latest.localId, clampVolume(settings.gain ?? 1) * volume)
}
