// SPDX-FileCopyrightText: 2026 Cossale <hello@auna.li>
// SPDX-License-Identifier: AGPL-3.0-or-later

package app.valenced.patches.discord

import app.reseam.patch.CompatiblePackage
import app.reseam.patch.invoke

// valenced-extended: VoiceCord の RVA 対応表（voicecord/signatures/rva.json）にあり、実機で確認済みの版
val DISCORD: CompatiblePackage = "com.discord"("352.17 - Stable")
