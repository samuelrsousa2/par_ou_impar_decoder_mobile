# Walkthrough - Como rodar o Par/Impar Decoder Mobile

O projeto Android foi criado com sucesso com as seguintes funcionalidades:
- **Bot Completo**: Mesma lógica do Python (Par/Impar, Martingale, Virtual Mode).
- **Foreground Service**: Roda em segundo plano com notificação persistente.
- **Auto-Reconnect**: Sistema robusto de reconexão.
- **UI Moderna**: Jetpack Compose com Dashboard e Logs.

## Pré-requisitos
- Android Studio Hedgehog ou superior.
- Java JDK 17 (configurado no Android Studio).

## Passos para Execução
1. Abra o Android Studio.
2. Selecione **Open** e aponte para a pasta `PROJETO_RAIZ` (`c:\Users\SamuelXD\Desktop\PAR_OU_IMPAR_DECODER_MOBILE`).
3. Aguarde o **Gradle Sync**.
4. Conecte um dispositivo Android via USB ou inicie um Emulador.
5. Clique no botão **Run** (Play verde).

## Verificação
1. **Configuração**: Na aba "Config", insira seus tokens (DEMO/REAL) e configure o Stake/Martingale.
2. **Iniciar**: Clique em "INICIAR BOT".
3. **Background**: Minimize o app.
   - Veja a notificação "Par Impar Decoder - Bot Iniciado" na barra de status.
4. **Dashboard**: Volte ao app e vá na aba "Dashboard" para ver os Logs de Mercado e Operações.

## Solução de Problemas
- **Erro de Build/Gradle**: Verifique se o JDK 17 está selecionado em `Settings > Build, Execution, Deployment > Build Tools > Gradle`.
- **Bot não conecta**: Verifique sua conexão de internet. O log mostrará tentativas de reconexão.
