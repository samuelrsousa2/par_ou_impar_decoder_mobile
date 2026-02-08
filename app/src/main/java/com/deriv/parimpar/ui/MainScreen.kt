package com.deriv.parimpar.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import com.deriv.parimpar.AccountType
import com.deriv.parimpar.BotConfig
import com.deriv.parimpar.MainViewModel
import com.deriv.parimpar.OpStatus
import com.deriv.parimpar.TradeOperation
import com.deriv.parimpar.TriggerMode

@Composable
fun MainScreen(viewModel: MainViewModel) {
    val operations by viewModel.operations.collectAsState()
    val isRunning by viewModel.isRunning.collectAsState()
    val statusText by viewModel.statusText.collectAsState()
    val balance by viewModel.balance.collectAsState()
    val totalProfit by viewModel.totalProfit.collectAsState()
    val config by viewModel.uiConfig.collectAsState()

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
    ) {
        // --- Status Header ---
        Card(
            modifier = Modifier.fillMaxWidth().padding(16.dp),
            colors = CardDefaults.cardColors(
                containerColor = if (isRunning) Color(0xFFE8F5E9) else Color(0xFFFFEBEE)
            )
        ) {
            Column(modifier = Modifier.padding(16.dp)) {
                Text(
                    text = if (isRunning) "BOT ATIVO" else "BOT PARADO",
                    style = MaterialTheme.typography.titleMedium,
                    color = if (isRunning) Color(0xFF2E7D32) else Color(0xFFC62828)
                )
                Spacer(modifier = Modifier.height(8.dp))
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Text(
                        text = "Saldo: $${String.format("%.2f", balance)}",
                        style = MaterialTheme.typography.bodyLarge
                    )
                    Text(
                        text = "Lucro: $${String.format("%.2f", totalProfit)}",
                        color = if (totalProfit >= 0) Color(0xFF2E7D32) else Color(0xFFC62828),
                        style = MaterialTheme.typography.bodyLarge
                    )
                }
            }
        }

        // --- Configuração (Só aparece quando parado) ---
        if (!isRunning) {
            ConfigSection(config) { newConfig -> viewModel.updateConfig(newConfig) }
        }

        // --- Botões de Controle ---
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 8.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Button(
                onClick = { viewModel.toggleStartStop() },
                modifier = Modifier.weight(1f),
                colors = ButtonDefaults.buttonColors(
                    containerColor = if (isRunning) Color(0xFFC62828) else Color(0xFF2E7D32)
                )
            ) {
                Text(if (isRunning) "PARAR" else "INICIAR")
            }

            if (!isRunning) {
                OutlinedButton(
                    onClick = { viewModel.resetStats() },
                    modifier = Modifier.weight(1f)
                ) {
                    Text("RESETAR")
                }
            }
        }

        Divider(modifier = Modifier.padding(vertical = 8.dp))

        // --- Lista de Resultados (Única área visual de operações) ---
        Text(
            text = "Resultados",
            style = MaterialTheme.typography.titleSmall,
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp)
        )
        
        LazyColumn(
            modifier = Modifier.fillMaxSize().padding(horizontal = 16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            items(operations, key = { it.id }) { op ->
                OperationCard(op)
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ConfigSection(config: BotConfig, onUpdate: (BotConfig) -> Unit) {
    // Estados locais para os campos
    var demoToken by remember { mutableStateOf(config.demoToken) }
    var realToken by remember { mutableStateOf(config.realToken) }
    var stake by remember { mutableStateOf(config.stake.toString()) }
    var maxGale by remember { mutableStateOf(config.maxGale.toString()) }
    var mult by remember { mutableStateOf(config.mult.toString()) }
    var stopWin by remember { mutableStateOf(config.stopWin.toString()) }
    var virtualMode by remember { mutableStateOf(config.virtualMode) }
    var vwinTarget by remember { mutableStateOf(config.vwinTarget.toString()) }
    var vlossTarget by remember { mutableStateOf(config.vlossTarget.toString()) }
    var triggerMode by remember { mutableStateOf(config.triggerMode) }
    
    // Dropdown state
    var expandedTrigger by remember { mutableStateOf(false) }

    // Atualiza config no ViewModel sempre que mudar
    LaunchedEffect(demoToken, realToken, stake, maxGale, mult, stopWin, virtualMode, vwinTarget, vlossTarget, triggerMode) {
        onUpdate(
            config.copy(
                demoToken = demoToken,
                realToken = realToken,
                stake = stake.toDoubleOrNull() ?: 1.0,
                maxGale = maxGale.toIntOrNull() ?: 0,
                mult = mult.toDoubleOrNull() ?: 2.0,
                stopWin = stopWin.toDoubleOrNull() ?: 0.0,
                virtualMode = virtualMode,
                vwinTarget = vwinTarget.toIntOrNull() ?: 0,
                vlossTarget = vlossTarget.toIntOrNull() ?: 0,
                triggerMode = triggerMode
            )
        )
    }

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp)
            .verticalScroll(rememberScrollState())
    ) {
        // --- Tokens ---
        Text("Autenticação", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary)
        Spacer(modifier = Modifier.height(4.dp))
        OutlinedTextField(
            value = demoToken,
            onValueChange = { demoToken = it },
            label = { Text("Token DEMO") },
            modifier = Modifier.fillMaxWidth(),
            singleLine = true
        )
        Spacer(modifier = Modifier.height(4.dp))
        OutlinedTextField(
            value = realToken,
            onValueChange = { realToken = it },
            label = { Text("Token REAL") },
            modifier = Modifier.fillMaxWidth(),
            singleLine = true
        )

        Spacer(modifier = Modifier.height(16.dp))

        // --- Modo Gatilho (Dropdown) ---
        Text("Estratégia", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary)
        Spacer(modifier = Modifier.height(4.dp))
        
        ExposedDropdownMenuBox(
            expanded = expandedTrigger,
            onExpandedChange = { expandedTrigger = !expandedTrigger },
            modifier = Modifier.fillMaxWidth()
        ) {
            OutlinedTextField(
                modifier = Modifier.menuAnchor().fillMaxWidth(),
                readOnly = true,
                value = triggerMode.name,
                onValueChange = {},
                label = { Text("Modo") },
                trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = expandedTrigger) }
            )
            ExposedDropdownMenu(
                expanded = expandedTrigger,
                onDismissRequest = { expandedTrigger = false }
            ) {
                DropdownMenuItem(
                    text = { Text("SEQUENCIA") },
                    onClick = { triggerMode = TriggerMode.SEQUENCIA; expandedTrigger = false }
                )
                DropdownMenuItem(
                    text = { Text("REVERSAO") },
                    onClick = { triggerMode = TriggerMode.REVERSAO; expandedTrigger = false }
                )
            }
        }

        Spacer(modifier = Modifier.height(8.dp))

        // --- Modo Virtual + Targets ---
        Row(verticalAlignment = Alignment.CenterVertically) {
            Checkbox(checked = virtualMode, onCheckedChange = { virtualMode = it })
            Text("Modo Virtual")
        }

        if (virtualMode) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                OutlinedTextField(
                    value = vwinTarget,
                    onValueChange = { vwinTarget = it },
                    label = { Text("Win Virtual") },
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                    modifier = Modifier.weight(1f),
                    singleLine = true
                )
                OutlinedTextField(
                    value = vlossTarget,
                    onValueChange = { vlossTarget = it },
                    label = { Text("Loss Virtual") },
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                    modifier = Modifier.weight(1f),
                    singleLine = true
                )
            }
        }

        Spacer(modifier = Modifier.height(16.dp))

        // --- Gestão de Banca ---
        Text("Gestão de Banca", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary)
        Spacer(modifier = Modifier.height(4.dp))

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            OutlinedTextField(
                value = stake,
                onValueChange = { stake = it },
                label = { Text("Stake") },
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                modifier = Modifier.weight(1f),
                singleLine = true
            )
            OutlinedTextField(
                value = maxGale,
                onValueChange = { maxGale = it },
                label = { Text("Gale Máx") },
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                modifier = Modifier.weight(1f),
                singleLine = true
            )
        }

        Spacer(modifier = Modifier.height(8.dp))

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            OutlinedTextField(
                value = mult,
                onValueChange = { mult = it },
                label = { Text("Multiplicador") },
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                modifier = Modifier.weight(1f),
                singleLine = true
            )
            OutlinedTextField(
                value = stopWin,
                onValueChange = { stopWin = it },
                label = { Text("Stop Win") },
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                modifier = Modifier.weight(1f),
                singleLine = true
            )
        }

        Spacer(modifier = Modifier.height(16.dp))
    }
}

/**
 * Card de Operação - Campos visíveis conforme especificação:
 * - Ativo (symbol)
 * - Direção (PAR / IMPAR)
 * - Conta (DEMO / REAL)
 * - Stake
 * - Status (OPEN, WIN, LOSS)
 * - Lucro
 *
 * CAMPOS PROIBIDOS (não exibidos): Hora, Gale, Logs técnicos, IDs, Mensagens de erro
 */
@Composable
fun OperationCard(op: TradeOperation) {
    val backgroundColor = when (op.status) {
        OpStatus.WIN -> Color(0xFFC8E6C9)
        OpStatus.LOSS -> Color(0xFFFFCDD2)
        OpStatus.OPEN -> Color(0xFFFFF9C4)
        else -> Color.LightGray
    }

    val statusColor = when (op.status) {
        OpStatus.WIN -> Color(0xFF2E7D32)
        OpStatus.LOSS -> Color(0xFFC62828)
        else -> Color.Gray
    }

    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = backgroundColor)
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(12.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            // Coluna Esquerda: Symbol, Direction, Account
            Column {
                Text(text = op.symbol, style = MaterialTheme.typography.titleSmall)
                Text(
                    text = "${op.direction} • ${op.account}",
                    style = MaterialTheme.typography.bodySmall,
                    color = Color.Gray
                )
            }

            // Coluna Direita: Stake, Status, Profit
            Column(horizontalAlignment = Alignment.End) {
                Text(
                    text = "$${String.format("%.2f", op.stake)}",
                    style = MaterialTheme.typography.bodyMedium
                )
                Text(
                    text = when (op.status) {
                        OpStatus.OPEN -> "OPEN"
                        OpStatus.WIN -> "WIN"
                        OpStatus.LOSS -> "LOSS"
                        OpStatus.ERROR -> "ERROR"
                    },
                    style = MaterialTheme.typography.labelMedium,
                    color = statusColor
                )
                if (op.status != OpStatus.OPEN) {
                    Text(
                        text = "$${op.profit}",
                        style = MaterialTheme.typography.titleSmall,
                        color = statusColor
                    )
                }
            }
        }
    }
}
