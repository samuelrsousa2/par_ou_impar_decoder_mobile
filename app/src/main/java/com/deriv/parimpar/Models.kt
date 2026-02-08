package com.deriv.parimpar

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonElement

// --- Enums ---
enum class TriggerMode {
    SEQUENCIA, REVERSAO
}

enum class OpStatus {
    OPEN, WIN, LOSS, ERROR
}

enum class AccountType {
    DEMO, REAL
}

// --- Configuração ---
data class BotConfig(
    val demoToken: String = "",
    val realToken: String = "",
    val triggerMode: TriggerMode = TriggerMode.SEQUENCIA,
    val virtualMode: Boolean = true,
    val vwinTarget: Int = 0,
    val vlossTarget: Int = 0,
    val stake: Double = 1.0,
    val maxGale: Int = 0,
    val mult: Double = 2.0,
    val stopWin: Double = 0.0
)

// --- Estado da UI ---
data class TradeOperation(
    val id: String,
    val time: String,
    val symbol: String,
    val account: AccountType,
    val direction: String, // "PAR" ou "IMPAR"
    val stake: Double,
    val status: OpStatus,
    val profit: String,
    val contractId: String? = null
)

// --- Deriv API Messages ---

@Serializable
data class AuthorizeRequest(
    val authorize: String,
    val req_id: Int? = null
)

@Serializable
data class TicksRequest(
    val ticks: String,
    val subscribe: Int = 1,
    val req_id: Int? = null
)

@Serializable
data class ProposalRequest(
    val proposal: Int = 1,
    val amount: Double,
    val basis: String = "stake",
    @SerialName("contract_type") val contractType: String,
    val currency: String = "USD",
    val duration: Int = 1,
    @SerialName("duration_unit") val durationUnit: String = "t",
    val symbol: String,
    val req_id: Int? = null
)

@Serializable
data class BuyRequest(
    val buy: String,
    val price: Double,
    val req_id: Int? = null
)

@Serializable
data class POCRequest(
    @SerialName("proposal_open_contract") val proposalOpenContract: Int = 1,
    @SerialName("contract_id") val contractId: Long,
    val req_id: Int? = null
)

@Serializable
data class BalanceRequest(
    val balance: Int = 1,
    val subscribe: Int = 1,
    val req_id: Int? = null
)

// --- Deriv API Responses ---

@Serializable
data class BaseResponse(
    @SerialName("msg_type") val msgType: String? = null,
    val error: ErrorResponse? = null,
    @SerialName("req_id") val reqId: Int? = null,
    val tick: TickData? = null,
    val proposal: ProposalData? = null,
    val buy: BuyData? = null,
    @SerialName("proposal_open_contract") val poc: POCData? = null,
    val balance: BalanceData? = null
)

@Serializable
data class ErrorResponse(
    val code: String,
    val message: String
)

@Serializable
data class TickData(
    val symbol: String,
    val quote: Double,
    val epoch: Long,
    @SerialName("pip_size") val pipSize: Int
)

@Serializable
data class ProposalData(
    val id: String,
    @SerialName("payout") val payout: Double? = null
)

@Serializable
data class BuyData(
    @SerialName("contract_id") val contractId: Long,
    @SerialName("buy_price") val buyPrice: Double
)

@Serializable
data class POCData(
    @SerialName("is_sold") val isSold: Int? = 0,
    val status: String? = null, // "won", "lost"
    val profit: Double? = null,
    @SerialName("contract_id") val contractId: Long? = null
)

@Serializable
data class BalanceData(
    val balance: Double,
    val currency: String,
    @SerialName("loginid") val loginId: String
)
