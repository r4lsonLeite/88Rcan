# 88Rcan — Live portátil de baixo custo (câmera → celular → YouTube)

Sistema experimental para transmitir ao vivo no YouTube usando **qualquer câmera com RTSP/H.264**
e um **celular Android com internet móvel** como ponte — sem notebook, sem placa de captura,
sem depender de marca de câmera.

```
Câmera IP (RTSP, H.264+AAC) ──Wi-Fi──▶ Celular Android (88Rcan Live) ──4G/5G, RTMPS──▶ YouTube Live
```

O celular **não recodifica** o vídeo: ele só reempacota RTSP → RTMP. Por isso usa pouca CPU,
pouca bateria, esquenta pouco e mantém a qualidade original da câmera.

## O que já existe

| Pasta | Conteúdo |
|---|---|
| [`core/`](core) | Motor de streaming em Kotlin puro (sem dependências): cliente RTSP (Basic/Digest, RTP sobre TCP), depacketizadores H.264/AAC, publicador RTMP/**RTMPS**, reconexão automática, controle de congestionamento, estatísticas. Também roda em PC/Raspberry Pi pela linha de comando. |
| [`android/`](android) | App de operação: tela de configuração e status, serviço em primeiro plano com WakeLock/WifiLock, notificação com botão Parar, roteamento câmera-pelo-Wi-Fi + YouTube-pelos-dados-móveis, bateria e temperatura no painel. |
| [`tools/`](tools) | Câmera RTSP simulada e teste de ponta a ponta local. |
| [`docs/`](docs) | [Arquitetura](docs/arquitetura.md) · [Câmeras compatíveis](docs/cameras-compativeis.md) · [Equipamentos](docs/equipamentos.md) · [Roteiro de testes até a 1ª live](docs/roteiro-de-testes.md) |

## Começo rápido

### 1. Testar o motor no PC (JDK 17+, ffmpeg)

```bash
cd core
./gradlew test                 # testes unitários
../tools/e2e_local.sh          # câmera simulada → motor → servidor RTMP local
```

Com uma câmera real e sua chave do YouTube:

```bash
./gradlew installDist
build/install/core/bin/core \
  --source "rtsp://usuario:senha@192.168.1.10:554/stream1" \
  --target "rtmps://a.rtmps.youtube.com/live2/SUA-CHAVE"
```

### 2. App Android

1. Abra a pasta `android/` no **Android Studio** (ele usa o `core/` automaticamente via *composite build*).
2. Rode no celular (Android 8.0+).
3. Preencha a URL RTSP da câmera e a chave do YouTube Studio → **Iniciar live**.

## Requisitos da câmera (resumo)

RTSP com **H.264** (H.265 ainda não), áudio **AAC** se quiser som, keyframe a cada **2 s**,
bitrate **CBR** de 60–70 % do upload disponível. Detalhes e URLs por fabricante em
[docs/cameras-compativeis.md](docs/cameras-compativeis.md).

## Situação

- ✅ Motor validado localmente: testes unitários e pipeline ponta a ponta (H.264 720p30 + AAC, 0 erros de decodificação) e reconexão automática após queda do servidor.
- ⚠️ App Android escrito mas **ainda não compilado/testado em aparelho** (o ambiente de desenvolvimento não tinha o Android SDK). Primeira tarefa da Fase 3 do roteiro.
- ⏳ Testes com câmera real, YouTube e 4G: ver [roteiro](docs/roteiro-de-testes.md).
