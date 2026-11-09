import Page from '@valenced/components/Page'
import { Design } from '@valenced/discord/design'
import { useState } from 'react'
import { ScrollView, View } from 'react-native'
import { Message, SoundList, useAction, useVoiceState } from './controls'
import { addSound, changeVolume, defaults, playOther, removeSound, renameSound, setMonitor } from './library'
import * as Native from './native'
import type { Sound, Storage, VoiceCordStorage } from './library'
import type { PluginSettingsComponent } from '@valenced/plugins/types'

function Rename({ sound, storage, disabled, run }: {
    sound: Sound
    storage: VoiceCordStorage
    disabled: boolean
    run: (action: () => unknown | Promise<unknown>) => Promise<void>
}) {
    const [name, setName] = useState(sound.name)
    return (
        <View style={{ gap: 8 }}>
            <Design.TextInput label="音源名" value={name} onChange={setName} />
            <Design.Button text="名前を保存" size="sm" variant="secondary"
                disabled={disabled || !name.trim() || name.trim() === sound.name}
                onPress={() => run(() => renameSound(storage, sound.id, name))} />
            <Design.Button text="ライブラリから削除" size="sm" variant="destructive"
                disabled={disabled} onPress={() => run(() => removeSound(storage, sound.id))} />
        </View>
    )
}

const Settings: PluginSettingsComponent<{ jsonStorage: Storage }> = ({ api }) => {
    const storage = api.jsonStorage
    const settings = storage.use()
    const { value: status, error } = useVoiceState()
    const { busy, message, run } = useAction()
    const [soundId, setSoundId] = useState('')
    const canPlay = !!status?.supported && !!status.hooked

    return (
        <ScrollView keyboardShouldPersistTaps="handled">
            <Page spacing={20}>
                <View style={{ gap: 8 }}>
                    <Design.Text variant="heading-lg/semibold">動作状況</Design.Text>
                    <Message text={error} />
                    {status && <>
                        <Message text={status.supported ? '対応バージョンです' : 'この Discord バージョンは未対応'} />
                        <Message text={`フック: ${status.hooked ? '接続済み' : '未接続'} / バージョンコード: ${status.versionCode}`} />
                        <Message text={status.playing ? '再生中' : '停止中'} />
                    </>}
                    <Message text="通話相手の同意を得たうえで使用してください。" />
                </View>
                <Design.TableRowGroup>
                    <Design.TableSwitchRow label="押したときに自分にも聞こえる"
                        subLabel="サウンドボードや音源ライブラリから再生した音を、通話に送ると同時に自分の端末でも鳴らします。"
                        value={settings?.monitor ?? true}
                        onValueChange={on => run(() => setMonitor(storage, on))} />
                </Design.TableRowGroup>
                <View style={{ gap: 12 }}>
                    <Design.Text variant="heading-lg/semibold">音量</Design.Text>
                    {(['gain', 'duck'] as const).map(key => {
                        const value = settings?.[key] ?? 1
                        return <View key={key} style={{ gap: 8 }}>
                            <Message text={`${key === 'gain' ? '音源の音量' : 'マイクの音量'}: ${value.toFixed(1)}`} />
                            <View style={{ flexDirection: 'row', gap: 8 }}>
                                <Design.Button text="下げる" size="sm" variant="secondary"
                                    disabled={busy || value <= 0}
                                    onPress={() => run(() => changeVolume(storage, key, value - 0.1))} />
                                <Design.Button text="上げる" size="sm" variant="secondary"
                                    disabled={busy || value >= 2}
                                    onPress={() => run(() => changeVolume(storage, key, value + 0.1))} />
                            </View>
                        </View>
                    })}
                </View>
                <View style={{ gap: 12 }}>
                    <Design.Text variant="heading-lg/semibold">音源ライブラリ</Design.Text>
                    <Design.Button text="ファイルを追加" disabled={busy} onPress={() => run(async () => {
                        const file = await Native.pickFile()
                        if (!file) return
                        const result = await addSound(storage, file)
                        return result.duplicate ? `「${result.sound.name}」は登録済みです。` : '音源を追加しました。'
                    })} />
                    <SoundList storage={storage} canPlay={canPlay} run={run} busy={busy}
                        renderActions={sound => <Rename sound={sound} storage={storage} disabled={busy} run={run} />} />
                </View>
                <View style={{ gap: 12 }}>
                    <Design.Text variant="heading-lg/semibold">サウンドボード</Design.Text>
                    <Design.TextInput label="サウンドボード ID" value={soundId} onChange={setSoundId}
                        placeholder="1〜20 桁の数字" maxLength={20} />
                    <Design.Button text="サウンドボードを再生" disabled={busy || !canPlay || !/^\d{1,20}$/.test(soundId)}
                        onPress={() => run(() => playOther(settings ?? defaults, () => Native.playSoundboard(soundId)))} />
                    <Design.Button text="テスト音を再生" variant="secondary" disabled={busy || !canPlay}
                        onPress={() => run(() => playOther(settings ?? defaults, Native.playTestTone))} />
                </View>
                <Message text={message} />
            </Page>
        </ScrollView>
    )
}

export default Settings
