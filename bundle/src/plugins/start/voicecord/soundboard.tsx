import { Tokens } from '@valenced/discord/common/tokens'
import { Design } from '@valenced/discord/design'
import { getModuleWithImportedPath } from '@valenced/discord/utils/modules/finders'
import { after } from '@valenced/patcher'
import { Children, cloneElement, isValidElement } from 'react'
import { Pressable, ScrollView, View } from 'react-native'
import { Message, useAction, useVoiceState } from './controls'
import * as Native from './native'
import type { VoiceCordStorage } from './library'
import type { PluginCleanupApi } from '@valenced/plugins/types'
import type { ReactElement, ReactNode } from 'react'

const { Text, Button, createStyles } = Design

// 352.17 で確認したパス。ピッカー本体は memo で、type が描画関数。
const PickerPath = 'modules/soundboard/native/SoundboardSoundPicker.tsx'
const ListPath = 'modules/soundboard/native/SoundboardSoundPickerList.tsx'

type Memo = { type: (props: unknown) => ReactNode }

function VoiceCordSection({ storage }: { storage: VoiceCordStorage }) {
    const styles = useStyles()
    const { value, error } = useVoiceState()
    const { busy, message, run } = useAction()
    const sounds = storage.use()?.sounds ?? []
    const canPlay = !!value?.hooked && !!value.supported
    return (
        <View style={styles.section}>
            <View style={styles.header}>
                <Text variant="text-sm/semibold">🎙 VoiceCord</Text>
                <Button text="停止" size="sm" variant="secondary" disabled={!canPlay}
                    onPress={() => run(Native.stop)} />
            </View>
            <Message text={error} />
            {value && !value.supported && <Message text="この Discord バージョンは VoiceCord 未対応です。" />}
            {!sounds.length
                ? <Message text="音源がありません。VoiceCord の設定で追加してください。" />
                : (
                    <ScrollView horizontal showsHorizontalScrollIndicator={false}
                        keyboardShouldPersistTaps="always" contentContainerStyle={styles.row}>
                        {sounds.map(sound => (
                            <Pressable key={sound.id} accessibilityRole="button" accessibilityLabel={sound.name}
                                disabled={!canPlay || busy} style={[styles.button, (!canPlay || busy) && styles.disabled]}
                                onPress={() => run(() => Native.playUri(sound.uri))}>
                                <View style={styles.emoji}><Text variant="text-md/normal">🎵</Text></View>
                                <Text variant="text-sm/semibold" lineClamp={1}>{sound.name}</Text>
                            </Pressable>
                        ))}
                    </ScrollView>
                )}
            <Message text={message} />
        </View>
    )
}

const useStyles = createStyles({
    // 寸法は 352.17 の Discord サウンドのボタンに合わせた値
    section: { width: '100%', paddingHorizontal: 10, paddingBottom: 12, gap: 8 },
    header: { flexDirection: 'row', alignItems: 'center', justifyContent: 'space-between' },
    row: { gap: 8 },
    button: {
        width: 148,
        alignItems: 'center',
        gap: 8,
        paddingVertical: 12,
        paddingHorizontal: 8,
        borderRadius: 16,
        borderWidth: 1,
        borderColor: Tokens.default.colors.BORDER_SUBTLE,
        backgroundColor: Tokens.default.colors.CARD_BACKGROUND_DEFAULT,
    },
    disabled: { opacity: 0.5 },
    emoji: {
        width: 40,
        height: 40,
        borderRadius: 20,
        alignItems: 'center',
        justifyContent: 'center',
        backgroundColor: Tokens.default.colors.BACKGROUND_MOD_SUBTLE,
    },
})

function contains(node: ReactNode, type: unknown, depth = 6): boolean {
    if (Array.isArray(node)) return node.some(child => contains(child, type, depth))
    if (!isValidElement<{ children?: ReactNode }>(node)) return false
    if (node.type === type) return true
    return depth > 0 && contains(node.props.children, type, depth - 1)
}

/**
 * 一覧に最も近い兄弟配列で、一覧側の要素の直前に section を入れる。入れられなければ null。
 * BottomSheet 直下は [本体, カテゴリ] の配列なので、浅い配列より深い配列を優先する。
 */
function insertBeforeList(node: ReactNode, type: unknown, section: ReactElement, depth = 6): ReactElement | null {
    if (depth < 0 || !isValidElement<{ children?: ReactNode }>(node)) return null
    const { children } = node.props
    if (Array.isArray(children)) {
        const items = Children.toArray(children)
        const index = items.findIndex(child => contains(child, type))
        if (index < 0) return null
        const inner = insertBeforeList(items[index], type, section, depth - 1)
        if (inner) items[index] = inner
        else items.splice(index, 0, section)
        return cloneElement(node, undefined, ...items)
    }
    const next = insertBeforeList(children, type, section, depth - 1)
    return next && cloneElement(node, undefined, next)
}

export function installSoundboardSection(
    storage: VoiceCordStorage,
    cleanup: PluginCleanupApi,
    warn: (message: string, error?: unknown) => void,
) {
    // ピッカーは一覧より先に読み込み完了を通知するので、一覧の中で待つと取りこぼす。別々に待つ。
    let listType: unknown
    cleanup(getModuleWithImportedPath<{ SoundboardSoundPickerList?: unknown }>(ListPath, list => {
        listType = list?.SoundboardSoundPickerList
    }))
    cleanup(getModuleWithImportedPath<{ default?: Memo }>(PickerPath, picker => {
        const memo = picker?.default
        if (typeof memo?.type !== 'function') {
            warn('サウンドボードの形式が異なるため、VoiceCord を追加しません。')
            return
        }
        let warned = false
        cleanup(after(memo, 'type', result => {
            try {
                const next = listType
                    ? insertBeforeList(result, listType, <VoiceCordSection key="voicecord" storage={storage} />)
                    : null
                if (next) return next
                if (!warned) warn('サウンドボードの構造が異なるため、VoiceCord を追加しません。')
            } catch (e) {
                if (!warned) warn('サウンドボードに VoiceCord を追加できません。', e)
            }
            warned = true
            return result
        }))
    }))
}
