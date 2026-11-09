import {
    callNativeMethod,
    callNativeMethodSync,
    registerJSMethod,
} from '@valenced/modules/native'

export interface VoiceCordState {
    hooked: boolean
    playing: boolean
    supported: boolean
    versionCode: number
}

export interface PlaybackResult {
    ok: boolean
    error?: string
}

export interface PickedFile {
    uri: string
    name: string
    size: number
    hash: string
}

export interface PlaybackState {
    playing: boolean
}

declare module '@valenced/modules/native' {
    interface NativeMethods {
        'voicecord.state': [[], VoiceCordState]
        'voicecord.playUri': [[uri: string], PlaybackResult]
        'voicecord.playSoundboard': [[soundId: string], PlaybackResult]
        'voicecord.stop': [[], null]
        'voicecord.setParams': [[gain: number, duck: number], null]
        'voicecord.playTestTone': [[], PlaybackResult]
        'voicecord.pickFile': [[], PickedFile | null]
        'voicecord.subscribe': [[], null]
        'voicecord.previewUri': [[uri: string, volume: number], PlaybackResult]
        'voicecord.setPreviewVolume': [[volume: number], null]
        'voicecord.stopPreview': [[], null]
    }
}

export const state = () => callNativeMethodSync('voicecord.state', [])
export const playUri = (uri: string) =>
    callNativeMethod('voicecord.playUri', [uri])
export const playSoundboard = (soundId: string) =>
    callNativeMethod('voicecord.playSoundboard', [soundId])
export const stop = () => callNativeMethodSync('voicecord.stop', [])
export const setParams = (gain: number, duck: number) =>
    callNativeMethodSync('voicecord.setParams', [gain, duck])
export const playTestTone = () =>
    callNativeMethodSync('voicecord.playTestTone', [])
export const pickFile = () => callNativeMethod('voicecord.pickFile', [])
/** 端末だけで鳴らす（VC には送らない）。volume は 0〜1。 */
export const previewUri = (uri: string, volume: number) =>
    callNativeMethod('voicecord.previewUri', [uri, volume])
export const setPreviewVolume = (volume: number) =>
    callNativeMethodSync('voicecord.setPreviewVolume', [volume])
export const stopPreview = () => callNativeMethodSync('voicecord.stopPreview', [])

const listeners = new Set<(value: PlaybackState) => void>()
let registered = false

export function subscribe() {
    if (!registered) {
        // 停止後のネイティブ通知でも未登録メソッドを呼ばないよう保持する。
        registerJSMethod('voicecord.onState', (value: PlaybackState) => {
            for (const listener of listeners) listener(value)
        })
        registered = true
    }
    callNativeMethodSync('voicecord.subscribe', [])
}

export function onState(listener: (value: PlaybackState) => void) {
    listeners.add(listener)
    return () => { listeners.delete(listener) }
}
