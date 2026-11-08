import { SheetHeader } from '@valenced/components'
import { ActionSheetActionCreators } from '@valenced/discord/actions'
import { Design } from '@valenced/discord/design'
import { getModules } from '@valenced/modules/finders'
import { withProps } from '@valenced/modules/finders/filters'
import { after } from '@valenced/patcher'
import { Children, cloneElement, isValidElement } from 'react'
import { ScrollView, View } from 'react-native'
import { Message, SoundList, useAction, useVoiceState } from './controls'
import type { VoiceCordStorage } from './library'
import type { PluginCleanupApi } from '@valenced/plugins/types'
import type { ReactNode } from 'react'

const SheetKey = 'VOICECORD_LIBRARY'

function Panel({ storage }: { storage: VoiceCordStorage }) {
    const { value, error } = useVoiceState()
    const { busy, message, run } = useAction()
    return (
        <Design.BottomSheet header={<SheetHeader title="VoiceCord" />}>
            <ScrollView contentContainerStyle={{ padding: 16, paddingBottom: 32, gap: 12 }}>
                <Message text={error} />
                {value && <Message text={!value.supported ? 'この Discord バージョンは未対応' : value.playing ? '再生中' : '停止中'} />}
                <SoundList storage={storage} canPlay={!!value?.hooked && !!value.supported}
                    busy={busy} run={run} />
                <Message text={message} />
            </ScrollView>
        </Design.BottomSheet>
    )
}

type CallControlsModule = {
    VoiceCallControls: (...args: unknown[]) => ReactNode
}

export function installPanel(
    storage: VoiceCordStorage,
    cleanup: PluginCleanupApi,
    warn: (message: string, error?: unknown) => void,
) {
    try {
        // 版依存の候補。未検出・構造変更時も設定画面は利用可能。
        warn('VC ボタンは VoiceCallControls の検出待ちです。表示されない場合は設定画面を使用してください。')
        cleanup(getModules(withProps<CallControlsModule>('VoiceCallControls'), module => {
            try {
                if (!module || typeof module.VoiceCallControls !== 'function') {
                    warn('VC 操作コンポーネントの形式が異なるため、ボタンを追加しません。')
                    return
                }
                let warned = false
                cleanup(after(module, 'VoiceCallControls', result => {
                    try {
                        if (!isValidElement<{ children?: ReactNode }>(result) ||
                            !Object.prototype.hasOwnProperty.call(result.props, 'children')) {
                            if (!warned) {
                                warned = true
                                warn('VC 操作画面の構造が異なるため、ボタンを追加しません。')
                            }
                            return result
                        }
                        return cloneElement(result, undefined,
                            ...Children.toArray(result.props.children),
                            <View key="voicecord" style={{ padding: 8 }}>
                                <Design.Button text="VoiceCord" size="sm" variant="secondary"
                                    onPress={() => {
                                        try {
                                            ActionSheetActionCreators.openLazy(
                                                Promise.resolve({ default: Panel }), SheetKey, { storage }, 'stack',
                                            )
                                        } catch (e) { warn('VoiceCord パネルを開けません。設定画面を使用してください。', e) }
                                    }} />
                            </View>,
                        )
                    } catch (e) {
                        if (!warned) {
                            warned = true
                            warn('VC ボタンを追加できません。設定画面を使用してください。', e)
                        }
                        return result
                    }
                }))
            } catch (e) { warn('VC 操作画面をパッチできません。設定画面を使用してください。', e) }
        }, { max: 1 }))
        cleanup(() => {
            try { ActionSheetActionCreators.hideActionSheet(SheetKey) }
            catch (e) { warn('VoiceCord パネルを閉じられませんでした。', e) }
        })
    } catch (e) { warn('VC 操作画面を検索できません。設定画面を使用してください。', e) }
}
