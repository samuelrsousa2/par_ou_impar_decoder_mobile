package com.deriv.parimpar

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * ViewModel principal que conecta UI ao TradingEngine.
 * Usa AndroidViewModel para acessar Context e persistir configurações.
 */
class MainViewModel(application: Application) : AndroidViewModel(application) {
    
    private val engine = TradingEngine()
    private val configRepo = ConfigRepository(application)

    // Expose Engine State
    val isRunning = engine.isRunning
    val operations = engine.operations
    val balance = engine.balance
    val totalProfit = engine.totalProfit
    val statusText = engine.statusText

    // UI Configuration State (carregado do storage)
    private val _uiState = MutableStateFlow(configRepo.load())
    val uiConfig = _uiState.asStateFlow()

    fun updateConfig(newConfig: BotConfig) {
        _uiState.value = newConfig
        // Salva automaticamente ao atualizar
        configRepo.save(newConfig)
    }

    fun toggleStartStop() {
        if (isRunning.value) {
            engine.stop()
        } else {
            engine.start(_uiState.value)
        }
    }

    fun resetStats() {
        if (isRunning.value) return 
        // start() do engine já reseta o estado interno
    }
}
