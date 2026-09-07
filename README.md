# Mic Monitor

Transmite o som do microfone do celular para outro aparelho pela rede Wi-Fi, sem instalar
programa nenhum e sem driver de áudio no computador. A escuta acontece dentro do navegador.

Ao contrário dos aplicativos parecidos, este tenta abrir o microfone em estéreo quando o
aparelho tem suporte, e avisa na tela quando o estéreo não é real.

## Como usar

1. Ligue o celular e o computador na mesma rede Wi-Fi.
2. Abra o aplicativo e toque em **Iniciar**.
3. Digite no navegador do computador o endereço que aparece na tela.
4. Na página que abrir, clique em **Iniciar**.

Antes desse clique a página não pede áudio nem ocupa a vaga de ouvinte. Depois dele o som
começa e os ajustes aparecem. Ganho, microfone, estéreo, buffer e silenciar ficam na própria
página, e as mudanças valem no mesmo instante nos dois lados.

Apenas um computador ouve por vez. Quando você fecha a aba, a vaga fica livre.

## Estado do projeto

| Item | Situação |
| --- | --- |
| Android mínimo | 7.0 |
| Formato na rede | PCM 16 bits, 48 kHz |
| Buffer de áudio | 60 a 400 ms, ajustável |
| Reprodução no navegador | trechos agendados na linha do tempo do áudio |
| Idiomas | português do Brasil e de Portugal, inglês, espanhol, francês, italiano, alemão, árabe, híndi, russo, vietnamita |
| Escolha da placa de som pelo navegador | indisponível em conexão simples |

A troca de placa de som pela página depende de conexão segura, que esta versão não usa.
Enquanto isso, a saída se escolhe pelo misturador de volume do sistema.

## Compilar

### O que precisa estar instalado

| Ferramenta | Versão |
| --- | --- |
| JDK | 17 |
| Gradle | 8.9 ou mais recente |
| Android SDK, plataforma | android-35 |
| Android SDK, ferramentas de compilação | 35.0.0 |

O Android Studio já traz o SDK e o JDK, mas não é obrigatório. Serve também instalar apenas
as ferramentas de linha de comando do Android e um JDK 17 avulso.

### Apontar o SDK

O projeto precisa saber onde o SDK do Android está. Escolha um dos dois caminhos.

Criar um arquivo `local.properties` na raiz do projeto, com o caminho do SDK:

```
sdk.dir=/caminho/para/o/android/sdk
```

Ou definir a variável de ambiente `ANDROID_HOME` apontando para a mesma pasta.

Se a plataforma e as ferramentas de compilação ainda não estiverem instaladas, use o
gerenciador do SDK:

```
sdkmanager "platform-tools" "platforms;android-35" "build-tools;35.0.0"
```

### Gerar o APK

Na raiz do projeto:

```
gradle assembleRelease
```

O arquivo sai em:

```
app/build/outputs/apk/release/app-release.apk
```

Para uma compilação de depuração, com registro de erros mais falante:

```
gradle assembleDebug
```

### Instalar no celular

Com o aparelho ligado por cabo e a depuração por USB ativada:

```
adb install -r app/build/outputs/apk/release/app-release.apk
```

Sem cabo, copie o APK para o celular e abra o arquivo por lá. O Android vai pedir permissão
para instalar de fonte desconhecida.

## Assinatura

A chave de assinatura não faz parte do repositório. Sem ela, os comandos acima funcionam
igual e produzem um APK sem assinatura, que serve para estudar o código mas não instala
no aparelho.

Para gerar um APK instalável, crie a sua própria chave e coloque o arquivo em
`app/micmonitor.p12`:

```
keytool -genkeypair -v -storetype PKCS12 -keystore app/micmonitor.p12 \
        -alias micmonitor -keyalg RSA -keysize 2048 -validity 20000
```

Depois informe as senhas por variáveis de ambiente e compile:

```
export MIC_MONITOR_STORE_PASSWORD=sua-senha
export MIC_MONITOR_KEY_ALIAS=micmonitor
export MIC_MONITOR_KEY_PASSWORD=sua-senha
gradle assembleRelease
```

Quem compila com uma chave própria gera um aplicativo que o Android considera diferente
do publicado nas versões deste repositório. Para trocar de um para o outro é preciso
desinstalar antes.

## Estrutura

| Caminho | Conteúdo |
| --- | --- |
| `app/src/main/java/.../MainActivity.kt` | tela principal, menu e diálogos |
| `app/src/main/java/.../SettingsActivity.kt` | tela de configurações |
| `app/src/main/java/.../StreamService.kt` | serviço em primeiro plano que sustenta a transmissão |
| `app/src/main/java/.../AudioEngine.kt` | captura do microfone e ganho |
| `app/src/main/java/.../MicServer.kt` | servidor HTTP e WebSocket embarcado |
| `app/src/main/assets/web/` | página servida ao navegador do computador |
| `app/src/main/res/values*/strings.xml` | textos, um arquivo por idioma |
