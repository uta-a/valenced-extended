import { registerInternalPlugin } from '@valenced/plugins/_'
import { applyParams, defaults } from './library'
import manifest from './manifest.json'
import * as Native from './native'
import Settings from './settings'
import { installSoundboardSection } from './soundboard'
import type { Storage } from './library'
import type {} from '@valenced/json-storage/types'

registerInternalPlugin<{ jsonStorage: Storage }>(manifest, {
    jsonStorage: { default: defaults },
    SettingsComponent: Settings,
    async start({ jsonStorage, cleanup, logger }) {
        let stopped = false
        cleanup(() => {
            stopped = true
            try { Native.stop() }
            catch (e) { logger.warn('再生を停止できませんでした。', e) }
        })
        try { Native.subscribe() }
        catch (e) { logger.warn('再生状態を購読できませんでした。', e) }

        try {
            const settings = await jsonStorage.get()
            if (stopped) return
            try { applyParams(settings) }
            catch (e) { logger.warn('保存した音量を適用できませんでした。', e) }
        } catch (e) { logger.warn('音源ライブラリを読み込めませんでした。', e) }

        if (!stopped) installSoundboardSection(jsonStorage, cleanup, (message, error) => logger.warn(message, error))
    },
})
