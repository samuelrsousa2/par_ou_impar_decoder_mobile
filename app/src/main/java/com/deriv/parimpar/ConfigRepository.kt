package com.deriv.parimpar

import android.content.Context
import android.content.SharedPreferences

/**
 * Repositório para persistir e recuperar configurações do bot.
 * Usa SharedPreferences (modo privado) para armazenamento local.
 */
class ConfigRepository(context: Context) {
    
    private val prefs: SharedPreferences = 
        context.getSharedPreferences("bot_config", Context.MODE_PRIVATE)

    companion object {
        private const val KEY_DEMO_TOKEN = "demo_token"
        private const val KEY_REAL_TOKEN = "real_token"
        private const val KEY_TRIGGER_MODE = "trigger_mode"
        private const val KEY_VIRTUAL_MODE = "virtual_mode"
        private const val KEY_VWIN_TARGET = "vwin_target"
        private const val KEY_VLOSS_TARGET = "vloss_target"
        private const val KEY_STAKE = "stake"
        private const val KEY_MAX_GALE = "max_gale"
        private const val KEY_MULT = "mult"
        private const val KEY_STOP_WIN = "stop_win"
    }

    /**
     * Salva toda a configuração no SharedPreferences.
     */
    fun save(config: BotConfig) {
        prefs.edit().apply {
            putString(KEY_DEMO_TOKEN, config.demoToken)
            putString(KEY_REAL_TOKEN, config.realToken)
            putString(KEY_TRIGGER_MODE, config.triggerMode.name)
            putBoolean(KEY_VIRTUAL_MODE, config.virtualMode)
            putInt(KEY_VWIN_TARGET, config.vwinTarget)
            putInt(KEY_VLOSS_TARGET, config.vlossTarget)
            putFloat(KEY_STAKE, config.stake.toFloat())
            putInt(KEY_MAX_GALE, config.maxGale)
            putFloat(KEY_MULT, config.mult.toFloat())
            putFloat(KEY_STOP_WIN, config.stopWin.toFloat())
            apply()
        }
    }

    /**
     * Carrega a configuração salva ou retorna valores padrão.
     */
    fun load(): BotConfig {
        return BotConfig(
            demoToken = prefs.getString(KEY_DEMO_TOKEN, "") ?: "",
            realToken = prefs.getString(KEY_REAL_TOKEN, "") ?: "",
            triggerMode = try {
                TriggerMode.valueOf(prefs.getString(KEY_TRIGGER_MODE, "SEQUENCIA") ?: "SEQUENCIA")
            } catch (e: Exception) {
                TriggerMode.SEQUENCIA
            },
            virtualMode = prefs.getBoolean(KEY_VIRTUAL_MODE, true),
            vwinTarget = prefs.getInt(KEY_VWIN_TARGET, 0),
            vlossTarget = prefs.getInt(KEY_VLOSS_TARGET, 0),
            stake = prefs.getFloat(KEY_STAKE, 1.0f).toDouble(),
            maxGale = prefs.getInt(KEY_MAX_GALE, 0),
            mult = prefs.getFloat(KEY_MULT, 2.0f).toDouble(),
            stopWin = prefs.getFloat(KEY_STOP_WIN, 0.0f).toDouble()
        )
    }
}
