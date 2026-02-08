package com.deriv.parimpar

import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.json.Json
import kotlinx.serialization.encodeToString
import java.util.concurrent.ConcurrentHashMap
import java.time.LocalTime
import java.time.format.DateTimeFormatter
import kotlin.math.absoluteValue

class TradingEngine {
    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private val json = Json { 
        ignoreUnknownKeys = true
        encodeDefaults = true  // Serializa campos com valores default (crítico para API Deriv)
        explicitNulls = false  // NÃO serializa campos null (req_id: null causa erro)
    }

    // --- UI State ---
    private val _isRunning = MutableStateFlow(false)
    val isRunning = _isRunning.asStateFlow()

    private val _operations = MutableStateFlow<List<TradeOperation>>(emptyList())
    val operations = _operations.asStateFlow()

    private val _balance = MutableStateFlow(0.0)
    val balance = _balance.asStateFlow()

    private val _totalProfit = MutableStateFlow(0.0)
    val totalProfit = _totalProfit.asStateFlow()

    private val _statusText = MutableStateFlow("Parado")
    val statusText = _statusText.asStateFlow()
    
    // --- Configuration ---
    var config = BotConfig()
        private set

    // --- WS Clients ---
    private lateinit var publicClient: DerivWSClient
    private lateinit var demoClient: DerivWSClient
    private lateinit var realClient: DerivWSClient

    // --- Internal State ---
    private var virtualWins = 0
    private var virtualLosses = 0
    private var isArmedForReal = false
    
    // Martingale (não mais usado - agora no loop local)
    private var currentGaleLevel = 0
    private var currentStake = 0.0
    
    // Trading Locks
    private val tradeMutex = Mutex()
    private var isTrading = false // Global lock to prevent overlap

    // Request Tracking com CompletableDeferred (igual Python await client.request())
    // Map reqId -> Deferred para Proposal
    private val pendingProposals = ConcurrentHashMap<Int, CompletableDeferred<String?>>() // ProposalId
    // Map reqId -> Deferred para Buy
    private val pendingBuys = ConcurrentHashMap<Int, CompletableDeferred<Long?>>() // ContractId
    // Map contractId -> Deferred para POC result
    private val pendingPocs = ConcurrentHashMap<Long, CompletableDeferred<Pair<String, Double>?>>() // (status, profit)

    companion object {
        // Lista de símbolos monitorados (idêntico ao Python)
        val ALLOWED_SYMBOLS = listOf(
            "R_10", "R_25", "R_50", "R_75", "R_100",
            "1HZ10V", "1HZ25V", "1HZ50V", "1HZ75V", "1HZ100V",
            "RDBEAR", "RDBULL"
        )
        private const val APP_ID = "122601"
    }

    fun start(cfg: BotConfig) {
        if (_isRunning.value) return
        config = cfg
        _isRunning.value = true
        _statusText.value = "Iniciando..."
        
        // Reset State
        virtualWins = 0
        virtualLosses = 0
        isArmedForReal = false
        currentGaleLevel = 0
        currentStake = config.stake
        _totalProfit.value = 0.0
        _operations.value = emptyList()
        pendingProposals.clear()
        pendingBuys.clear()
        pendingPocs.clear()
        isTrading = false

        // Init Clients
        publicClient = DerivWSClient("wss://ws.derivws.com/websockets/v3?app_id=$APP_ID", "PUBLIC") { msg -> processPublicMessage(msg) }
        demoClient = DerivWSClient("wss://ws.derivws.com/websockets/v3?app_id=$APP_ID", "DEMO") { msg -> processMessage(msg, AccountType.DEMO) }
        realClient = DerivWSClient("wss://ws.derivws.com/websockets/v3?app_id=$APP_ID", "REAL") { msg -> processMessage(msg, AccountType.REAL) }

        publicClient.connect()
        demoClient.connect()
        realClient.connect()

        scope.launch {
            delay(2000)
            authorizeClients()
            subscribeTicks()
            _statusText.value = "Monitorando..."
            updateVirtualStatus()
        }
    }

    fun stop() {
        _isRunning.value = false
        _statusText.value = "Parado"
        scope.launch {
            publicClient.stop()
            demoClient.stop()
            realClient.stop()
        }
    }

    private suspend fun authorizeClients() {
        if (config.demoToken.isNotEmpty()) {
            demoClient.send(json.encodeToString(AuthorizeRequest(authorize = config.demoToken)))
        }
        if (config.realToken.isNotEmpty()) {
            realClient.send(json.encodeToString(AuthorizeRequest(authorize = config.realToken)))
            delay(500)
            realClient.send(json.encodeToString(BalanceRequest()))
        }
    }

    private fun subscribeTicks() {
        // Subscribe em todos os símbolos (idêntico ao Python)
        ALLOWED_SYMBOLS.forEach { symbol ->
            publicClient.send(json.encodeToString(TicksRequest(ticks = symbol)))
        }
    }

    private fun processPublicMessage(msg: String) {
        if (!_isRunning.value) return
        try {
            val data = json.decodeFromString<BaseResponse>(msg)
            if (data.msgType == "tick") {
                data.tick?.let { analyzeAndTrade(it) }
            }
        } catch (e: Exception) { }
    }

    private fun analyzeAndTrade(tick: TickData) {
        // Only analyze if not busy
        if (isTrading) return

        val quoteStr = String.format("%.${tick.pipSize}f", tick.quote)
        val digits = quoteStr.filter { it.isDigit() }.map { it.toString().toInt() }
        
        if (digits.isEmpty()) return

        val parities = digits.map { if (it % 2 == 0) "PAR" else "IMPAR" }
        val uniform = if (parities.all { it == "PAR" }) "PAR"
                      else if (parities.all { it == "IMPAR" }) "IMPAR"
                      else null

        if (uniform != null) {
            val direction = if (config.triggerMode == TriggerMode.SEQUENCIA) uniform 
                            else if (uniform == "PAR") "IMPAR" else "PAR"

            triggerTrade(tick.symbol, direction)
        }
    }

    private fun triggerTrade(symbol: String, direction: String) {
        scope.launch {
            if (tradeMutex.isLocked || isTrading) return@launch
            
            tradeMutex.withLock {
                isTrading = true
                try {
                    val account = if (config.virtualMode && !isArmedForReal) AccountType.DEMO else AccountType.REAL
                    executeTradeSequence(symbol, direction, account)
                } catch (e: Exception) {
                    android.util.Log.e("TradingEngine", "Erro na execução: ${e.message}")
                } finally {
                    isTrading = false
                }
            }
        }
    }

    /**
     * Executa sequência completa de trades (igual ao Python):
     * 1. Faz proposal → buy → aguarda resultado
     * 2. Se WIN → para e marca WIN
     * 3. Se LOSS e tem gales → incrementa stake, repete
     * 4. Se LOSS e esgotou gales → para e marca LOSS
     * 5. Atualiza tabela apenas com resultado FINAL
     */
    private suspend fun executeTradeSequence(symbol: String, direction: String, account: AccountType) {
        val client = if (account == AccountType.REAL) realClient else demoClient
        // Usa config.stake para ambas as contas (REAL e DEMO), assim o Gale também segue a config
        val baseStake = config.stake
        
        val opId = System.currentTimeMillis().toString()
        val timeStr = LocalTime.now().format(DateTimeFormatter.ofPattern("HH:mm:ss"))
        
        var currentStakeLocal = baseStake
        var usedGale = 0
        var totalProfit = 0.0
        var finalStatus: OpStatus = OpStatus.OPEN
        
        // Adiciona operação na tabela como OPEN
        addOperation(TradeOperation(
            id = opId,
            time = timeStr,
            symbol = symbol,
            account = account,
            direction = direction,
            stake = baseStake,
            status = OpStatus.OPEN,
            profit = "..."
        ))
        
        android.util.Log.i("TradingEngine", "[$account] EXEC OPEN | $symbol | $direction | stake=$baseStake | max_gale=${config.maxGale}")
        
        // Loop Martingale (igual ao Python)
        while (_isRunning.value) {
            // 1. Proposal - usa req_id para correlação
            val contractType = if (direction == "PAR") "DIGITEVEN" else "DIGITODD"
            val proposalReqId = (System.currentTimeMillis() % 100000).toInt()
            val propReq = ProposalRequest(
                amount = currentStakeLocal,
                contractType = contractType,
                symbol = symbol,
                req_id = proposalReqId
            )
            
            val proposalDeferred = CompletableDeferred<String?>()
            pendingProposals[proposalReqId] = proposalDeferred
            client.send(json.encodeToString(propReq))
            
            val proposalId = try {
                withTimeoutOrNull(5000) { proposalDeferred.await() }
            } finally {
                pendingProposals.remove(proposalReqId)
            }
            
            if (proposalId == null) {
                android.util.Log.e("TradingEngine", "[$account] Proposal timeout/erro")
                finalStatus = OpStatus.ERROR
                break
            }
            
            // 2. Buy - usa req_id para correlação
            val buyReqId = (System.currentTimeMillis() % 100000).toInt()
            val buyReq = BuyRequest(buy = proposalId, price = currentStakeLocal, req_id = buyReqId)
            
            val buyDeferred = CompletableDeferred<Long?>()
            pendingBuys[buyReqId] = buyDeferred
            client.send(json.encodeToString(buyReq))
            
            val contractId = try {
                withTimeoutOrNull(5000) { buyDeferred.await() }
            } finally {
                pendingBuys.remove(buyReqId)
            }
            
            if (contractId == null) {
                android.util.Log.e("TradingEngine", "[$account] Buy timeout/erro")
                finalStatus = OpStatus.ERROR
                break
            }
            
            // 3. Aguarda resultado (poll POC até isSold=1) - usa contractId para correlação
            val pocDeferred = CompletableDeferred<Pair<String, Double>?>()
            pendingPocs[contractId] = pocDeferred
            
            // Inicia polling de POC em background
            scope.launch {
                val pocReq = POCRequest(contractId = contractId)
                val deadline = System.currentTimeMillis() + 60000
                while (System.currentTimeMillis() < deadline && _isRunning.value && !pocDeferred.isCompleted) {
                    client.send(json.encodeToString(pocReq))
                    delay(120) // Poll igual ao Python (0.12s)
                }
            }
            
            val result = try {
                withTimeoutOrNull(60000) { pocDeferred.await() }
            } finally {
                pendingPocs.remove(contractId)
            }
            
            if (result == null) {
                android.util.Log.e("TradingEngine", "[$account] POC timeout")
                finalStatus = OpStatus.ERROR
                break
            }
            
            val (status, profit) = result
            totalProfit += profit
            
            android.util.Log.i("TradingEngine", "[$account] Gale $usedGale: $status | profit=$profit | total=$totalProfit")
            
            if (status == "won") {
                finalStatus = OpStatus.WIN
                break
            }
            
            // LOSS - verificar se tem mais gales
            if (usedGale >= config.maxGale) {
                finalStatus = OpStatus.LOSS
                break
            }
            
            // Incrementar gale
            usedGale++
            currentStakeLocal = kotlin.math.round(currentStakeLocal * config.mult * 100) / 100 // Arredondar para 2 casas decimais
            android.util.Log.i("TradingEngine", "[$account] GALE $usedGale/${config.maxGale} | novo stake=$currentStakeLocal")
        }
        
        // Atualiza tabela com resultado FINAL
        updateOperation(opId, finalStatus, totalProfit)
        
        android.util.Log.i("TradingEngine", "[$account] EXEC CLOSE | $symbol | result=$finalStatus | profit_total=$totalProfit | gales_used=$usedGale")
        
        // Lógica pós-trade
        onTradeSequenceFinished(account, finalStatus, totalProfit)
    }
    
    // Map ProposalID -> Pending Operation (waiting for Buy confirmation) - NÃO MAIS USADO
    // private val pendingOpMap = ConcurrentHashMap<String, TradeOperation>()

    private fun processMessage(msg: String, account: AccountType) {
        if (!_isRunning.value) return
        try {
            val data = json.decodeFromString<BaseResponse>(msg)

            // Proposal - completa o Deferred usando req_id
            if (data.msgType == "proposal") {
                val reqId = data.reqId
                val proposalId = data.proposal?.id
                android.util.Log.d("TradingEngine", "[$account] PROPOSAL resp: reqId=$reqId, proposalId=$proposalId, pending=${pendingProposals.keys}")
                
                // Se há erro, logar e completar com null
                if (data.error != null) {
                    android.util.Log.e("TradingEngine", "[$account] PROPOSAL ERROR: ${data.error.code} - ${data.error.message}")
                    if (reqId != null) {
                        pendingProposals[reqId]?.complete(null)
                    }
                    return
                }
                
                if (reqId != null && proposalId != null) {
                    val deferred = pendingProposals[reqId]
                    if (deferred != null) {
                        deferred.complete(proposalId)
                        android.util.Log.d("TradingEngine", "[$account] PROPOSAL COMPLETED: reqId=$reqId")
                    } else {
                        android.util.Log.w("TradingEngine", "[$account] PROPOSAL NO MATCH: reqId=$reqId not in pending")
                    }
                }
            }

            // Buy Response - completa o Deferred usando req_id
            if (data.msgType == "buy") {
                val reqId = data.reqId
                val contractId = data.buy?.contractId
                android.util.Log.d("TradingEngine", "[$account] BUY resp: reqId=$reqId, contractId=$contractId, pending=${pendingBuys.keys}")
                if (reqId != null && contractId != null) {
                    val deferred = pendingBuys[reqId]
                    if (deferred != null) {
                        deferred.complete(contractId)
                        android.util.Log.d("TradingEngine", "[$account] BUY COMPLETED: reqId=$reqId, contractId=$contractId")
                    } else {
                        android.util.Log.w("TradingEngine", "[$account] BUY NO MATCH: reqId=$reqId not in pending")
                    }
                }
            }

            // POC (Proposal Open Contract) - completa o Deferred usando contractId
            if (data.msgType == "proposal_open_contract") {
                val poc = data.poc ?: return
                val contractId = poc.contractId ?: return
                if (poc.isSold == 1) {
                    val profit = poc.profit ?: 0.0
                    val status = if (profit >= 0) "won" else "lost"
                    android.util.Log.d("TradingEngine", "[$account] POC SOLD: contractId=$contractId, status=$status, profit=$profit, pending=${pendingPocs.keys}")
                    val deferred = pendingPocs[contractId]
                    if (deferred != null) {
                        deferred.complete(Pair(status, profit))
                        android.util.Log.d("TradingEngine", "[$account] POC COMPLETED: contractId=$contractId")
                    }
                }
            }
            
            // Balance
            if (data.msgType == "balance") {
                data.balance?.let { _balance.value = it.balance }
            }

        } catch (e: Exception) { 
            android.util.Log.w("TradingEngine", "Erro parse: ${e.message}")
        }
    }

    // monitorContract removido - polling agora é feito inline no executeTradeSequence

    /**
     * Lógica pós-trade (igual ao Python).
     * Gale já é tratado no loop executeTradeSequence - aqui só atualizamos contadores.
     */
    private fun onTradeSequenceFinished(account: AccountType, status: OpStatus, totalProfit: Double) {
        if (account == AccountType.REAL) {
            _totalProfit.value += totalProfit
            
            // Reset para próximo sinal
            isArmedForReal = false
            
            // Check Stop Win
            if (config.stopWin > 0 && _totalProfit.value >= config.stopWin) {
                stop()
                _statusText.value = "Stop Win Atingido!"
            }
        } else {
            // DEMO / Virtual - atualizar streaks
            if (config.virtualMode) {
                if (status == OpStatus.WIN) {
                    virtualWins++
                    virtualLosses = 0
                } else if (status == OpStatus.LOSS) {
                    virtualLosses++
                    virtualWins = 0
                }
                
                // Verificar se deve armar para REAL
                if (config.vwinTarget > 0 && virtualWins >= config.vwinTarget) isArmedForReal = true
                if (config.vlossTarget > 0 && virtualLosses >= config.vlossTarget) isArmedForReal = true
                
                // Se fez real, reseta
                if (account == AccountType.REAL) {
                    isArmedForReal = false
                    virtualWins = 0
                    virtualLosses = 0
                }
            }
        }
        updateVirtualStatus()
    }
    
    private fun updateVirtualStatus() {
        if (config.virtualMode) {
            _statusText.value = "Virtual: W:$virtualWins L:$virtualLosses | Real? ${if(isArmedForReal) "SIM" else "NÃO"}"
        } else {
            _statusText.value = "Modo Real Direto"
        }
    }

    private fun addOperation(op: TradeOperation) {
        _operations.value = listOf(op) + _operations.value
    }

    private fun updateOperation(id: String, status: OpStatus, profit: Double) {
        val list = _operations.value.toMutableList()
        val index = list.indexOfFirst { it.id == id }
        if (index != -1) {
            list[index] = list[index].copy(status = status, profit = String.format("%.2f", profit))
            _operations.value = list
        }
    }
}
