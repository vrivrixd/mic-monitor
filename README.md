# Mic Monitor

Transmite o som do microfone do celular para outro aparelho pela rede Wi-Fi, sem instalar
programa nenhum e sem driver de áudio no computador. A escuta acontece dentro do navegador.

Ao contrário dos aplicativos parecidos, este tenta abrir o microfone em estéreo quando o
aparelho tem suporte, e avisa na tela quando o estéreo não é real.

## Como usar

1. Ligue o celular e o computador na mesma rede Wi-Fi.
2. Abra o aplicativo e toque em **Iniciar**.
3. Digite no navegador do computador o endereço que aparece na tela.

A página abre e o áudio começa a tocar. Ganho, microfone, estéreo e silenciar ficam na
própria página, e as mudanças valem no mesmo instante nos dois lados.

Apenas um computador ouve por vez. Quando você fecha a aba, a vaga fica livre.

## Estado do projeto

| Item | Situação |
| --- | --- |
| Android mínimo | 7.0 |
| Formato na rede | PCM 16 bits, 48 kHz |
| Atraso típico | 150 a 250 ms |
| Escolha da placa de som pelo navegador | indisponível em conexão simples |

A troca de placa de som pela página depende de conexão segura, que esta versão não usa.
Enquanto isso, a saída se escolhe pelo misturador de volume do sistema.

## Compilar

O APK sai do GitHub Actions, sem precisar de Android Studio. A cada envio para a ramificação
principal o fluxo **Compilar APK** gera o arquivo e publica como artefato da execução.

Para compilar na própria máquina, com Java 17 e o SDK do Android instalados:

```
gradle assembleDebug
```

## Autor

Vitor Bruski.
