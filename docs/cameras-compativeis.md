# Câmeras compatíveis

O app não depende de marca. A regra é uma só:

> **A câmera precisa oferecer um fluxo RTSP com vídeo H.264 ou H.265 (HEVC).**
> Áudio é opcional; para ir ao YouTube precisa ser **AAC**.
>
> H.265 é enviado ao YouTube via *Enhanced RTMP* (FourCC `hvc1`). H.264 continua sendo a opção
> mais compatível; prefira-o quando a câmera permitir escolher.

## Requisitos e configuração recomendada na câmera

| Item | Obrigatório | Recomendado para live no 4G |
|---|---|---|
| Protocolo | RTSP (porta 554 normalmente) | aceitar RTP sobre TCP (quase todas aceitam) |
| Codec de vídeo | **H.264** ou **H.265** | H.264 se der para escolher; desligar "Smart Codec"/"H.264+"/"H.265+" |
| Resolução | — | 1280×720 (estável no 4G) ou 1920×1080 com 4G bom |
| Quadros/s | — | 25 ou 30 |
| Intervalo de keyframe (GOP / I-frame interval) | ≤ 4 s | **2 s** (= 2 × fps, ex.: 60 em 30 fps) |
| Taxa de bits | — | **CBR**, 1500–2500 kbps em 720p; 3000–4500 kbps em 1080p |
| Áudio | — | AAC (se a câmera só tiver G.711, a live sai sem áudio na v0.1) |

> Regra prática do bitrate: use no máximo **60–70 % do upload medido** no local da live
> (teste com um app de medição no próprio celular, no ponto onde vai transmitir).

## Marcas e caminhos RTSP mais comuns

Os caminhos abaixo são os padrões mais conhecidos de cada fabricante. **Confirme sempre no manual/app
do seu modelo e firmware** e valide com o procedimento da próxima seção antes de comprar várias unidades.

| Fabricante / linha | Suporte RTSP | URL típica (fluxo principal) | Observações |
|---|---|---|---|
| Hikvision (e marcas OEM) | Sim | `rtsp://usuario:senha@IP:554/Streaming/Channels/101` | `102` = sub-fluxo (menor, bom para 4G fraco) |
| Dahua / Intelbras (linhas baseadas em Dahua) / Imou | Sim (maioria) | `rtsp://usuario:senha@IP:554/cam/realmonitor?channel=1&subtype=0` | `subtype=1` = sub-fluxo. Em Imou a senha costuma ser o código de segurança da etiqueta |
| TP-Link Tapo (C100, C200, C310…) | Sim | `rtsp://usuario:senha@IP:554/stream1` | Criar "conta da câmera" no app Tapo (Configurações avançadas). `stream2` = sub-fluxo |
| Reolink (linhas com RTSP) | Sim (exceto linhas só-bateria/nuvem) | `rtsp://admin:senha@IP:554/h264Preview_01_main` | Modelos 4K podem vir em H.265 — trocar para H.264 |
| Câmeras genéricas ONVIF | Geralmente | varia (`/onvif1`, `/live/ch00_0`, `/11`…) | Use um app/descoberta ONVIF para achar a URL |
| Câmeras do app **Yoosee** | Sim — ativar em *Configurações → Conexão NVR* e criar a senha RTSP | `rtsp://admin:SENHA_RTSP@IP:554/onvif1` (`onvif2` = sub-fluxo) | Testada: modelo dupla lente entrega **H.265 1920×2160 15 fps** + áudio **G.711** (sai sem som na v0.1). IP em *Informações do dispositivo* |
| Xiaomi / Mi Home | Normalmente **não** | — | Firmware oficial não expõe RTSP |
| Câmeras de ação (GoPro, DJI Osmo Action, Insta360) | **Não** como fonte RTSP | — | Elas próprias transmitem RTMP direto ao YouTube via hotspot do celular; não precisam deste app |
| Celular antigo como câmera | Depende do app | — | Funciona se o app de "câmera IP" oferecer RTSP com H.264 |

### Perfis de uso

- **Câmera fixa em tripé (evento, culto, obra, esporte amador):** câmera IP compacta com microfone (ex.: linhas Tapo/Imou/Intelbras) + hotspot do celular. Custo mais baixo.
- **Câmera externa/rua:** câmera bullet IP67 (Hikvision/Intelbras/Reolink) alimentada por power bank 12 V/PoE portátil.
- **Zoom óptico:** câmeras PTZ IP com RTSP — mesmo fluxo, apenas mais consumo de energia.

## Como validar uma câmera em 5 minutos (antes de ir a campo)

No PC, na mesma rede da câmera:

```bash
# 1) Ver codecs e resolução
ffprobe -rtsp_transport tcp "rtsp://usuario:senha@IP:554/caminho"
#    Procure:  Video: h264 ...   e   Audio: aac ...

# 2) Ver a imagem
ffplay -rtsp_transport tcp "rtsp://usuario:senha@IP:554/caminho"

# 3) Testar o motor do app sem celular (mais detalhes em roteiro-de-testes.md)
cd core && ./gradlew installDist
build/install/core/bin/core --source "rtsp://usuario:senha@IP:554/caminho" \
    --target "rtmps://a.rtmps.youtube.com/live2/SUA-CHAVE" --duration 60
```

Ou pelo VLC: *Mídia → Abrir fluxo de rede* → cole a URL → *Ferramentas → Informações do codec*.

| Resultado do `ffprobe` | Situação |
|---|---|
| `Video: h264` + `Audio: aac` | ✅ Compatível com vídeo e áudio |
| `Video: h264` + `pcm_alaw`/`pcm_mulaw` | ⚠️ Compatível só com vídeo (v0.1) |
| `Video: hevc` | ✅ Compatível via Enhanced RTMP (se puder escolher, H.264 é ainda mais seguro) |
| `401 Unauthorized` | usuário/senha errados ou conta RTSP não criada |
| `404 Not Found` | caminho errado — confira o padrão do fabricante |
| conexão recusada / timeout | IP errado, porta diferente de 554, ou RTSP desativado na câmera |

## Planilha de homologação (preencher nos testes)

| Modelo | Firmware | URL RTSP | Vídeo | Áudio | Live 30 min OK? | Consumo (W) | Observações |
|---|---|---|---|---|---|---|---|
| Yoosee dupla lente (Casa01) | 38.2.91 | `/onvif1` | H.265 1920×2160 15 fps | G.711 A-law 16 kHz | ⏳ | | RTSP sobre TCP OK; **YouTube aceitou H.265 via RTMPS (estado LIVE)** a partir do PC; não atualizar firmware antes de testar |
| | | | | | | | |
