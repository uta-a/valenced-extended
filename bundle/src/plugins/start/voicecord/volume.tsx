import { ActionSheetActionCreators } from '@valenced/discord/actions'
import { Design } from '@valenced/discord/design'
import { useState } from 'react'
import type { FC } from 'react'
import { View } from 'react-native'
import { changeSoundVolume, clampVolume, defaults } from './library'
import { setLiveSoundVolume } from './playback'
import type { Storage, VoiceCordStorage } from './library'

const { ActionSheet, BottomSheetTitleHeader, TableRowGroup, Text, Button } = Design

// 352.17 の Slider が読む props。@valenced/discord の SliderProps（minimumValue など）とは名前が違う。
interface DiscordSliderProps {
    value: number
    minValue: number
    maxValue: number
    step: number
    onChange: (value: number) => void
    onChangeEnd?: (value: number) => void
}
const Slider = Design.Slider as unknown as FC<DiscordSliderProps>

const SheetKey = 'VOICECORD_SOUND_VOLUME'

interface VolumeSheetProps {
    storage: VoiceCordStorage
    soundId: string
    warn: (message: string, error?: unknown) => void
}

const toPercent = (value: number) => Math.round(value * 100)

function VolumeSheet({ storage, soundId, warn }: VolumeSheetProps) {
    const settings = storage.use()
    const sound = settings?.sounds.find(item => item.id === soundId)
    const [value, setValue] = useState(clampVolume(sound?.volume ?? 1))

    // この音源が再生中なら、動かした音量をすぐ反映する
    const preview = (next: number) => {
        try { setLiveSoundVolume((settings ?? defaults) as Storage, soundId, next) }
        catch (e) { warn('音量を反映できませんでした。', e) }
    }
    const save = (next: number) => {
        changeSoundVolume(storage, soundId, next).catch(e => warn('音量を保存できませんでした。', e))
    }
    const set = (next: number) => {
        const clamped = clampVolume(next)
        setValue(clamped)
        preview(clamped)
        return clamped
    }

    if (!sound) return null
    return (
        <ActionSheet>
            <BottomSheetTitleHeader title={`🎵 ${sound.name}`} />
            <TableRowGroup title="音量">
                <View style={{ padding: 16, gap: 8 }}>
                    <Text variant="text-md/semibold">{toPercent(value)}%</Text>
                    <Slider step={10} value={toPercent(value)} minValue={0} maxValue={200}
                        onChange={next => set(next / 100)}
                        onChangeEnd={next => save(set(next / 100))} />
                    <Text variant="text-sm/medium" color="text-muted">
                        全体の「音源の音量」に掛け合わせます。100% が元の音量です。
                    </Text>
                </View>
            </TableRowGroup>
            <View style={{ paddingTop: 16 }}>
                <Button text="100% に戻す" variant="secondary" disabled={value === 1}
                    onPress={() => save(set(1))} />
            </View>
        </ActionSheet>
    )
}

export function openVolumeSheet(props: VolumeSheetProps) {
    try {
        ActionSheetActionCreators.openLazy(Promise.resolve({ default: VolumeSheet }), SheetKey, props, 'stack')
    } catch (e) {
        props.warn('音量の設定を開けません。', e)
    }
}

export function hideVolumeSheet() {
    try { ActionSheetActionCreators.hideActionSheet(SheetKey) }
    catch {}
}
