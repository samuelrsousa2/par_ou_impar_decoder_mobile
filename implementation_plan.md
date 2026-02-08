# Plano de Implementação - Par/Impar Decoder Mobile

Este plano detalha a criação de um aplicativo Android nativo que replica exatamente a funcionalidade do bot Python `par_impar_decoder_gui.py`.

## Objetivo
Criar um app Android que se conecta via WebSocket à API da Deriv, monitora ticks, executa estratégias de Par/Impar com lógica de "Virtual Mode" e Martingale, idêntico ao script Python original.

## Arquitetura
- **Linguagem**: Kotlin
- **UI Toolkit**: Jetpack Compose
- **Network**: OkHttp (WebSocket)
- **JSON**: Kotlinx Serialization
- **Padrão**: MVVM (Model-View-ViewModel)

## User Review Required
> [!IMPORTANT]
> Atendendo ao pedido do usuário, o app utilizará um **Foreground Service** para garantir que o bot continue rodando mesmo com a tela desligada ou o app minimizado. Uma notificação persistente informará o status do bot.

## Proposed Changes

### Estrutura do Projeto (Arquivos a serem criados)

#### [NEW] [build.gradle.kts (App)](file:///c:/Users/SamuelXD/Desktop/PAR_OU_IMPAR_DECODER_MOBILE/app/build.gradle.kts)
- Configuração de dependências: Compose, OkHttp, Kotlinx Serialization, Accompanist Permissions (para notificação).

#### [NEW] [AndroidManifest.xml](file:///c:/Users/SamuelXD/Desktop/PAR_OU_IMPAR_DECODER_MOBILE/app/src/main/AndroidManifest.xml)
- Permissões: Internet, Foreground Service.
- Declaração do `BotService`.

#### [NEW] [BotService.kt](file:///c:/Users/SamuelXD/Desktop/PAR_OU_IMPAR_DECODER_MOBILE/app/src/main/java/com/deriv/parimpar/BotService.kt)
- **Foreground Service**: Mantém o `TradingEngine` vivo.
- Gerencia a Notificação Persistente (ex: "Bot Rodando - Lucro: $5.00").
- Lógica de reconexão automática em caso de queda de internet ou WebSocket.

#### [NEW] [Models.kt](file:///c:/Users/SamuelXD/Desktop/PAR_OU_IMPAR_DECODER_MOBILE/app/src/main/java/com/deriv/parimpar/Models.kt)
- Data classes para requisições e respostas JSON da Deriv (Ticks, Proposals, Contracts).

#### [NEW] [DerivWSClient.kt](file:///c:/Users/SamuelXD/Desktop/PAR_OU_IMPAR_DECODER_MOBILE/app/src/main/java/com/deriv/parimpar/DerivWSClient.kt)
- Gerenciamento de conexão WebSocket persistente com **Backoff Exponencial** para reconexão.
- Detecta queda de rede e tenta reconectar automaticamente.
- Métodos `authorize`, `subscribeTicks`, `sendRequest`.

#### [NEW] [TradingEngine.kt](file:///c:/Users/SamuelXD/Desktop/PAR_OU_IMPAR_DECODER_MOBILE/app/src/main/java/com/deriv/parimpar/TradingEngine.kt)
- Lógica central portada do Python.
- Funções `digitsParityMap`, `allSameParity`.
- Controle de estado: `Virtual Mode`, `Real Mode`.
- Loop de execução de sinais e Martingale.

#### [NEW] [MainViewModel.kt](file:///c:/Users/SamuelXD/Desktop/PAR_OU_IMPAR_DECODER_MOBILE/app/src/main/java/com/deriv/parimpar/MainViewModel.kt)
- Ponte entre UI e TradingEngine.
- Mantém estado observável para a UI (Logs, Status, Configurações).

#### [NEW] [UIComponents.kt](file:///c:/Users/SamuelXD/Desktop/PAR_OU_IMPAR_DECODER_MOBILE/app/src/main/java/com/deriv/parimpar/UIComponents.kt)
- Composable functions para Logs, Tabela de Operações e Configurações Inputs.

#### [NEW] [MainActivity.kt](file:///c:/Users/SamuelXD/Desktop/PAR_OU_IMPAR_DECODER_MOBILE/app/src/main/java/com/deriv/parimpar/MainActivity.kt)
- Ponto de entrada do App. Configuração do tema e navegação básica.

## Verification Plan

### Verificação Manual
1.  **Compilação**: O usuário abrirá o projeto no Android Studio e executará o Build.
2.  **Foreground Service**: Minimizar o app e verificar se a notificação aparece e o bot continua rodando.
3.  **Reconexão**: Desligar o Wi-Fi/Dados, esperar a desconexão, ligar novamente e verificar se o bot reconecta e retoma.
4.  **Trading**: Verificar a lógica de operação (Virtual -> Real) e Martingale.
