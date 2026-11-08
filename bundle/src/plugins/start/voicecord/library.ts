import { setParams } from './native'
import type { JsonStorage } from '@valenced/json-storage'
import type { PickedFile } from './native'

export interface Sound extends PickedFile {
    id: string
    addedAt: number
}

export interface Storage {
    sounds: Sound[]
    gain: number
    duck: number
}

export const defaults: Storage = { sounds: [], gain: 1, duck: 1 }
export type VoiceCordStorage = JsonStorage<Storage>

export const clampVolume = (value: number) =>
    Number.isFinite(value) ? Math.min(2, Math.max(0, Math.round(value * 10) / 10)) : 1

export function applyParams(settings: Storage) {
    setParams(clampVolume(settings.gain ?? 1), clampVolume(settings.duck ?? 1))
}

// 配列の削除・並行追加を失わないよう、更新を直列化して全体を保存する。
let pending: Promise<unknown> = Promise.resolve()
function update<T>(storage: VoiceCordStorage, change: (data: Storage) => T) {
    const operation = pending.then(async () => {
        const current = storage.loaded ? storage.cache as Storage : await storage.get()
        const data: Storage = {
            ...defaults,
            ...current,
            sounds: [...(current?.sounds ?? [])],
        }
        const result = change(data)
        await storage.set(data, true)
        return result
    })
    pending = operation.catch(() => {})
    return operation
}

export function addSound(storage: VoiceCordStorage, file: PickedFile) {
    return update(storage, data => {
        const duplicate = data.sounds.find(sound =>
            sound.size === file.size && sound.hash === file.hash)
        if (duplicate) return { sound: duplicate, duplicate: true }
        const addedAt = Date.now()
        let id = `${addedAt}`
        while (data.sounds.some(sound => sound.id === id)) id += '-1'
        const sound = { ...file, id, addedAt }
        data.sounds.push(sound)
        return { sound, duplicate: false }
    })
}

export function removeSound(storage: VoiceCordStorage, id: string) {
    return update(storage, data => {
        data.sounds = data.sounds.filter(sound => sound.id !== id)
    })
}

export function renameSound(storage: VoiceCordStorage, id: string, name: string) {
    return update(storage, data => {
        const sound = data.sounds.find(sound => sound.id === id)
        if (sound && name.trim()) {
            data.sounds = data.sounds.map(item => item.id === id
                ? { ...item, name: name.trim() } : item)
        }
    })
}

export function changeVolume(storage: VoiceCordStorage, key: 'gain' | 'duck', value: number) {
    return update(storage, data => {
        data[key] = clampVolume(value)
        applyParams(data)
    })
}
