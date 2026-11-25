# Arquitetura

## Visão geral

```
┌──────────────┐   Wi-Fi local    ┌────────────────────────────┐   4G/5G (internet)   ┌─────────────┐
│  Câmera IP / │  RTSP (H.264 +   │  Celular Android           │  RTMPS (porta 443)   │  YouTube    │
│  câmera de   │ ───────────────▶ │  app 88Rcan Live           │ ───────────────────▶ │  Live       │
│  ação c/RTSP │  AAC) sobre TCP  │  (sem recodificar o vídeo) │                      │             │
└──────────────┘                  └────────────────────────────┘                      └─────────────┘
```

O celular funciona como uma **ponte**: recebe o vídeo já comprimido pela câmera e o reempacota
para o protocolo que o YouTube aceita. Ele **não decodifica nem recodifica** o vídeo.

### Por que não recodificar?

| | Recodificar no celular | Reempacotar (escolhido) |
|---|---|---|
| CPU / bateria | alta (encoder de hardware + decoder) | muito baixa (só cópia de bytes) |
| Aquecimento | significativo em lives longas | mínimo |
| Qualidade | perde uma geração | idêntica à da câmera |
| Latência | +200–500 ms | +poucos ms |
| Limitação | — | a câmera precisa entregar **H.264 ou H.265** (+ **AAC** p/ áudio) e o **bitrate é definido na câmera** |

A limitação é aceitável: praticamente toda câmera IP entrega H.264 ou H.265. H.265 vai ao YouTube pelo
*Enhanced RTMP* (o mesmo mecanismo que o OBS usa para HEVC), sem recodificar.

## Componentes

### `core/` — motor de streaming (Kotlin puro, roda em Android e PC)

| Pacote | Responsabilidade |
|---|---|
| `rtsp` | `RtspClient`: OPTIONS → DESCRIBE → SETUP → PLAY, RTP intercalado no TCP, Basic/Digest, keep-alive. `SdpParser`. |
| `rtp` | `H264Depacketizer` (RFC 6184) e `H265Depacketizer` (RFC 7798: AP, FU) sobre uma base comum que descarta frames após perda até o próximo keyframe; `AacDepacketizer` (RFC 3640). |
| `media` | Tipos de frame/config, `SpsParser` (H.264) e `HevcSpsParser` (H.265: resolução, perfil e campos do hvcC). |
| `rtmp` | `RtmpPublisher` (handshake, chunks, AMF0, RTMP e **RTMPS**, `fourCcList` do Enhanced RTMP), `FlvTags` (AVC clássico e HEVC via Enhanced RTMP `hvc1`). |
| `relay` | `RelayEngine`: orquestra tudo, reconexão automática, controle de congestionamento, estatísticas. |
| `cli` | Executável para PC/Raspberry Pi — o mesmo motor, útil para testes de bancada. |

Zero dependências externas: menos peso no APK, nenhuma biblioteca abandonada, tudo testável em JVM.

### `android/` — app de operação

| Classe | Função |
|---|---|
| `MainActivity` | Tela: URL/credenciais da câmera, servidor/chave do YouTube, opções, Iniciar/Parar, painel de status. |
| `LiveService` | Serviço em primeiro plano (`connectedDevice`): WakeLock + WifiLock, notificação com status e botão Parar, leitura de bateria/temperatura. |
| `NetworkRouter` | Amarra o socket da câmera ao **Wi-Fi** e o do YouTube aos **dados móveis** quando necessário. |
| `LiveState` | Estado observável compartilhado entre serviço e tela. |

## Topologias de rede suportadas

**A) Câmera conectada ao hotspot do celular (recomendada)**
O celular cria o roteador Wi-Fi; a câmera entra nele; a internet sai pelo 4G normalmente.
Nenhuma configuração especial. A câmera recebe IP do celular (ex.: `192.168.43.x` / `10.x`) — veja o
IP na lista de dispositivos conectados do hotspot ou no app da câmera.

**B) Celular conectado ao Wi-Fi da própria câmera (modo AP da câmera)**
Comum em câmeras de ação. O Wi-Fi da câmera não tem internet; por padrão o Android mandaria tudo por ele.
Marque **"Câmera no Wi-Fi dela, internet pelos dados móveis"**: o `NetworkRouter` pede as duas redes
(`ConnectivityManager.requestNetwork`) e cria cada socket na rede certa (`Network.getSocketFactory()`).
Ao conectar no Wi-Fi da câmera, responda "manter conexão" quando o Android avisar que a rede não tem internet.

**C) Roteador 4G portátil**
Câmera e celular no mesmo roteador 4G. Equivalente à topologia A, com antena/bateria separadas.

## Fluxo do `RelayEngine`

1. Conecta na câmera, lê o SDP e recebe SPS/PPS (do SDP ou do próprio fluxo).
2. Espera o primeiro **keyframe** e só então conecta ao YouTube (a live sempre começa limpa).
3. Envia `onMetaData`, sequence headers AVC/AAC e passa a repassar frames com timestamps em ms.
4. **Congestionamento:** se a rede móvel não acompanhar e a fila passar de `maxQueueMs` (3 s),
   descarta frames até o próximo keyframe — a live continua ao vivo em vez de acumular atraso.
5. **Falha** de qualquer lado → fecha tudo e reconecta com espera de 1 s, 2 s, 5 s, 10 s…
   (o contador zera depois de 60 s estável). O YouTube tolera reconexões curtas na mesma chave.

## Limitações conhecidas (v0.1) e próximos passos

- **H.265/HEVC:** suportado via Enhanced RTMP; validado localmente (ffmpeg como receptor), **ainda não validado no YouTube real**.
- **Áudio G.711 (PCMA/PCMU)**, comum em câmeras baratas: o YouTube não aceita; hoje a live segue só com vídeo.
  Próximo passo: transcodificar G.711 → AAC com `MediaCodec` (custo baixo, é só áudio).
- **B-frames:** assume DTS = PTS (câmeras IP normalmente não usam B-frames).
- **Sincronia A/V:** alinhada pelo instante de chegada; próximo passo: usar RTCP Sender Reports.
- **Câmera do próprio celular / USB (UVC):** fora do escopo desta versão; possível adicionar como outra "fonte".
- **Descoberta automática (ONVIF):** hoje a URL RTSP é digitada.
- **Chave da transmissão** em SharedPreferences privadas; migrar para Android Keystore.
