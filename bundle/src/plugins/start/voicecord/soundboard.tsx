import { getAssetIdByName } from '@valenced/assets'
import { getModuleWithImportedPath } from '@valenced/discord/utils/modules/finders'
import { after, instead } from '@valenced/patcher'
import { cloneElement, isValidElement } from 'react'
import { currentSoundId, onPlaybackChange, playSound } from './playback'
import { hideVolumeSheet, openVolumeSheet } from './volume'
import type { Sound, Storage, VoiceCordStorage } from './library'
import type { PluginCleanupApi } from '@valenced/plugins/types'
import type { ReactElement, ReactNode } from 'react'

// 352.17 で確認したパスと形。VoiceCord の音は Discord のカテゴリとして一覧に入れ、
// 描画・押下アニメーション・再生中表示は Discord の部品に任せる。押下時の処理だけ差し替える。
const GridPath = 'modules/soundboard/useSoundGrid.tsx'
const ListPath = 'modules/soundboard/native/SoundboardSoundPickerList.tsx'
const CategoriesPath = 'modules/soundboard/native/SoundboardSoundPickerCategories.tsx'
const ButtonPath = 'modules/soundboard/native/SoundButton.tsx'
const StorePath = 'modules/soundboard/SoundboardStore.tsx'

const CategoryKey = 'voicecord'
const Title = 'VoiceCord'
const SoundIdPrefix = 'voicecord:'
// Discord サウンドのカテゴリ種別。見出しとバーはこの種別の描画を元に、アイコンと名前だけ差し替える。
const DefaultCategoryType = 2
const DefaultSoundGuildId = '0'

interface DiscordSound {
    name: string
    volume: number
    soundId: string
    guildId: string
    emojiId: string | null
    emojiName: string
    userId: undefined
    available: boolean
}

interface Category {
    key: unknown
    categoryInfo: { type: number }
    items: Array<{ type: number; sound: DiscordSound; index: number }>
}

type Memo = { type: (...args: any[]) => ReactNode }
type Props = Record<string, any>

const isVoiceCordSound = (sound: { soundId?: unknown } | undefined) =>
    typeof sound?.soundId === 'string' && sound.soundId.startsWith(SoundIdPrefix)

const toDiscordSound = (sound: Sound): DiscordSound => ({
    name: sound.name,
    volume: 1,
    soundId: SoundIdPrefix + sound.id,
    guildId: DefaultSoundGuildId,
    emojiId: null,
    emojiName: '🎵',
    userId: undefined,
    available: true,
})

/** element から深さ優先で最初に一致した要素を置き換えた木を返す。見つからなければ null。 */
function replaceFirst(
    node: ReactNode,
    match: (element: ReactElement<Props>) => boolean,
    replace: (element: ReactElement<Props>) => ReactElement,
    depth = 8,
): ReactNode | null {
    if (Array.isArray(node)) {
        for (let i = 0; i < node.length; i++) {
            const next = replaceFirst(node[i], match, replace, depth)
            if (next !== null) return node.map((child, j) => (j === i ? next : child))
        }
        return null
    }
    if (depth < 0 || !isValidElement<Props>(node)) return null
    if (match(node)) return replace(node)
    const next = replaceFirst(node.props.children, match, replace, depth - 1)
    return next === null ? null : cloneElement(node, undefined, next)
}

const isIcon = (element: ReactElement<Props>) => typeof element.props.source === 'number'

export function installSoundboardSection(
    storage: VoiceCordStorage,
    cleanup: PluginCleanupApi,
    warn: (message: string, error?: unknown) => void,
) {
    const icon = getAssetIdByName('MicrophoneIcon', 'png')
    let store: { emitChange?: () => void } | undefined
    const playingId = () => {
        const id = currentSoundId()
        return id === undefined ? undefined : SoundIdPrefix + id
    }
    // 再生中の音源が変わったら、Discord のボタンに isPlayingSound を読み直させる
    let shown = playingId()
    cleanup(onPlaybackChange(() => {
        const next = playingId()
        if (next === shown) return
        shown = next
        store?.emitChange?.()
    }))
    cleanup(hideVolumeSheet)

    const sounds = () => {
        const data = storage.loaded ? (storage.cache as Storage) : undefined
        return (data?.sounds ?? []).map(toDiscordSound)
    }

    // 描画ごとに配列を作り直さないよう、元の結果と保存内容が同じ間は使い回す。
    let cached: { source: unknown; data: unknown; value: unknown } | undefined

    const warned = new Set<string>()
    const warnOnce = (message: string, error?: unknown) => {
        if (warned.has(message)) return
        warned.add(message)
        warn(message, error)
    }

    // 一覧・検索・カテゴリバーの元データ。先頭に VoiceCord のカテゴリを足す。
    cleanup(getModuleWithImportedPath<{ default?: (...args: any[]) => any }>(GridPath, grid => {
        if (typeof grid?.default !== 'function') return warnOnce('サウンドボードの音の一覧を見つけられません。')
        cleanup(after(grid as Required<typeof grid>, 'default', result => {
            const hit = cached
            if (hit && hit.source === result && hit.data === storage.cache) return hit.value
            try {
                const list = sounds()
                if (!list.length || !Array.isArray(result?.categories)) return result
                const category: Category = {
                    key: CategoryKey,
                    categoryInfo: { type: DefaultCategoryType },
                    items: list.map((sound, index) => ({ type: 0, sound, index })),
                }
                const value = {
                    ...result,
                    categories: [category, ...result.categories],
                    availableSounds: [...list, ...(result.availableSounds ?? [])],
                }
                cached = { source: result, data: storage.cache, value }
                return value
            } catch (e) {
                warnOnce('サウンドボードに VoiceCord の音を追加できません。', e)
                return result
            }
        }))
    }))

    // 一覧の見出し。Discord サウンドの見出しを元に、アイコンと名前を差し替える。
    cleanup(getModuleWithImportedPath<{ SoundboardSoundPickerList?: Memo }>(ListPath, list => {
        const memo = list?.SoundboardSoundPickerList
        if (typeof memo?.type !== 'function') return warnOnce('サウンドボードの一覧の形式が異なります。')
        cleanup(instead(memo, 'type', function (this: unknown, args: any[], original) {
            const result = original.apply(this, args)
            const categories: Category[] | undefined = args[0]?.categories
            const render = isValidElement<Props>(result) ? result.props.renderSection : undefined
            if (typeof render !== 'function' || !categories?.some(category => category.key === CategoryKey)) return result
            return cloneElement(result as ReactElement<Props>, {
                renderSection: (index: number, ...rest: unknown[]) => {
                    const header = render(index, ...rest)
                    if (categories[index]?.key !== CategoryKey) return header
                    const withIcon = icon == null ? header
                        : replaceFirst(header, isIcon, element => cloneElement(element, { source: icon })) ?? header
                    return replaceFirst(withIcon, element => element.props.accessibilityRole === 'header',
                        element => cloneElement(element, undefined, Title)) ?? withIcon
                },
            })
        }))
    }))

    // 下部のカテゴリバー。Discord サウンドの項目を元に、アイコンと名前を差し替える。
    cleanup(getModuleWithImportedPath<{ default?: Memo }>(CategoriesPath, categories => {
        const memo = categories?.default
        if (typeof memo?.type !== 'function') return warnOnce('サウンドボードのカテゴリバーの形式が異なります。')
        cleanup(after(memo, 'type', result => {
            const replaced = replaceFirst(result,
                element => typeof element.props.renderItem === 'function' && Array.isArray(element.props.data),
                element => cloneElement(element, {
                    renderItem: (info: { item?: Category }) => {
                        const item = element.props.renderItem(info)
                        if (info?.item?.key !== CategoryKey || !isValidElement<Props>(item) || !isFunctionComponent(item.type)) return item
                        return <CategoryItem element={item} icon={icon} />
                    },
                }))
            return replaced ?? result
        }))
    }))

    // 押下時だけ VoiceCord で再生する。再生中に押すと最初から再生し直し、長押しで音源ごとの音量を開く。
    cleanup(getModuleWithImportedPath<{ SoundButton?: Memo }>(ButtonPath, button => {
        const memo = button?.SoundButton
        if (typeof memo?.type !== 'function') return warnOnce('サウンドボードのボタンの形式が異なります。')
        let lastSound: DiscordSound | undefined
        cleanup(instead(memo, 'type', function (this: unknown, args: any[], original) {
            lastSound = args[0]?.sound
            const result = original.apply(this, args)
            if (!isVoiceCordSound(lastSound)) return result
            const sound = lastSound!
            return replaceFirst(result,
                element => typeof element.props.onPress === 'function' && typeof element.props.onLongPress === 'function',
                element => cloneElement(element, {
                    onPress: () => play(sound),
                    onLongPress: () => openVolumeSheet({
                        storage,
                        soundId: sound.soundId.slice(SoundIdPrefix.length),
                        warn,
                    }),
                })) ?? result
        }))
    }))

    function play(sound: DiscordSound) {
        const id = sound.soundId.slice(SoundIdPrefix.length)
        const stored = (storage.cache as Storage | undefined)?.sounds?.find(item => item.id === id)
        if (!stored) return
        // 古い要求の取り消し（cancelled）は、後から押した再生に置き換わっただけなので警告しない
        playSound(storage.cache as Storage, stored).then(result => {
            if (!result?.ok && result?.error !== 'cancelled') warn(`再生できませんでした: ${result?.error ?? ''}`)
        }, e => warn('再生できませんでした。', e))
    }

    // 再生中表示。Discord は isPlayingSound(soundId) で再生中の枠を描く。
    cleanup(getModuleWithImportedPath<{ default?: Record<string, any> }>(StorePath, module => {
        const instance = module?.default
        if (typeof instance?.isPlayingSound !== 'function') return warnOnce('サウンドボードの再生状態を取得できません。')
        store = instance
        cleanup(instead(instance, 'isPlayingSound', function (this: unknown, args: any[], original) {
            const [soundId] = args
            if (typeof soundId === 'string' && soundId.startsWith(SoundIdPrefix)) return playingId() === soundId
            return original.apply(this, args)
        }))
    }))
}

/** 関数として呼べる関数コンポーネントか。memo・forwardRef・クラスは元の項目のまま描画する。 */
const isFunctionComponent = (type: unknown): type is (props: Props) => ReactNode =>
    typeof type === 'function' && !(type as { prototype?: { isReactComponent?: unknown } }).prototype?.isReactComponent

/** Discord のカテゴリ項目を描画し、アイコンと読み上げ名だけ VoiceCord に替える。 */
function CategoryItem({ element, icon }: { element: ReactElement<Props>; icon: number | undefined }) {
    if (!isFunctionComponent(element.type)) return element
    const rendered = element.type(element.props)
    if (!isValidElement<Props>(rendered)) return rendered
    const relabeled = cloneElement(rendered, { accessibilityLabel: Title })
    if (icon == null) return relabeled
    return replaceFirst(relabeled, isIcon, iconElement => cloneElement(iconElement, { source: icon })) ?? relabeled
}
