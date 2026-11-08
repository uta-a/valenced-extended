import { Design } from '@valenced/discord/design'
import { useEffect, useState } from 'react'
import { View } from 'react-native'
import * as Native from './native'
import type { Sound, VoiceCordStorage } from './library'
import type { ReactNode } from 'react'

export function useVoiceState() {
    const [value, setValue] = useState<Native.VoiceCordState>()
    const [error, setError] = useState('')
    useEffect(() => {
        const remove = Native.onState(update => {
            setValue(previous => previous ? { ...previous, ...update } : previous)
        })
        try { setValue(Native.state()) }
        catch (e) { setError(`状態を取得できません: ${String(e)}`) }
        return remove
    }, [])
    return { value, error }
}

export function useAction() {
    const [busy, setBusy] = useState(false)
    const [message, setMessage] = useState('')
    async function run(action: () => unknown | Promise<unknown>) {
        setBusy(true)
        setMessage('')
        try {
            const result = await action()
            if (result && typeof result === 'object' && 'ok' in result && !result.ok) {
                throw new Error('error' in result ? String(result.error) : '再生できませんでした')
            }
            if (typeof result === 'string') setMessage(result)
        } catch (e) {
            setMessage(`操作に失敗しました: ${String(e)}`)
        } finally {
            setBusy(false)
        }
    }
    return { busy, message, run }
}

export function Message({ text }: { text: string }) {
    return text ? <Design.Text variant="text-sm/medium">{text}</Design.Text> : null
}

export function SoundList({ storage, canPlay, run, busy, renderActions }: {
    storage: VoiceCordStorage
    canPlay: boolean
    run: (action: () => unknown | Promise<unknown>) => Promise<void>
    busy: boolean
    renderActions?: (sound: Sound) => ReactNode
}) {
    const settings = storage.use()
    const sounds = settings?.sounds ?? []
    return (
        <View style={{ gap: 12 }}>
            <Design.Button text="再生を停止" variant="secondary" onPress={() => run(Native.stop)} />
            {!sounds.length && <Message text="音源がありません。VoiceCord の設定でファイルを追加してください。" />}
            {sounds.map(sound => (
                <View key={sound.id} style={{ gap: 8 }}>
                    <Design.Text variant="text-md/semibold">{sound.name}</Design.Text>
                    <Design.Button text="再生" size="sm" disabled={!canPlay || busy}
                        onPress={() => run(() => Native.playUri(sound.uri))} />
                    {renderActions?.(sound)}
                </View>
            ))}
        </View>
    )
}
