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
    }
    latest = request
    emit()
    const settle = (ok: boolean) => {
        if (latest !== request) return
        request.pending = false
        request.failed = !ok
        // 再生し直しでは状態が true のまま変わらず通知が来ないので、終了の通知まで再生中とみなす
        request.awaitingState = ok && !request.notified
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

export const playSound = (settings: Storage, sound: Sound) =>
    start(settings, sound.id, clampVolume(sound.volume ?? 1), () => Native.playUri(sound.uri))

/** ライブラリ外の音（テスト音・ID 指定のサウンドボード）を等倍で再生する。 */
export const playOther = (settings: Storage, run: () => PlaybackResult | Promise<PlaybackResult>) =>
    start(settings, undefined, 1, run)

/** 再生中の音源の倍率を変える。その音源が再生中でなければ何もしない。 */
export function setLiveSoundVolume(settings: Storage, soundId: string, volume: number) {
    if (currentSoundId() !== soundId || !latest) return
    latest.volume = volume
    applyParams(settings, volume)
}
