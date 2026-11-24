# Equipamentos

## Kit mínimo (primeira live)

| Item | Especificação | Por quê |
|---|---|---|
| Celular Android | Android 8.0+ (ideal 10+), 4G/5G, hotspot, 3 GB+ RAM | Ponte câmera → internet. Não precisa ser potente: o app não recodifica |
| Chip de dados | Plano com franquia suficiente (ver tabela abaixo) e boa cobertura no local | Upload é o gargalo da live |
| Câmera | RTSP + H.264 (ver [cameras-compativeis.md](cameras-compativeis.md)) | Fonte de vídeo |
| Alimentação da câmera | Fonte original **ou** power bank com saída compatível (5 V USB ou 12 V via cabo step-up/PD-trigger) | Lives longas sem tomada |
| Power bank do celular | 10.000–20.000 mAh, USB-C PD | Celular com hotspot + 4G consome bastante |
| Tripé/suporte | Para câmera e, se possível, para o celular em local com bom sinal | Estabilidade de imagem e de sinal |

## Itens recomendados

- **Roteador 4G portátil** (alternativa ao hotspot): antena melhor, tira o calor do celular, permite chip dedicado.
- **Antena externa / roteador com entrada de antena** para locais de sinal fraco.
- **Microfone** se a câmera tiver entrada de áudio (o áudio embutido de câmeras IP é fraco).
- **Cabo de rede curto + adaptador USB-C Ethernet**: opcional, para câmeras sem Wi-Fi (o Android usa Ethernet USB).
- **Capa/ventilação para o celular** em sol direto: temperatura é o principal motivo de queda em lives longas.

## Consumo de dados (estimativa)

Dados ≈ bitrate × tempo. Áudio AAC adiciona ~64–128 kbps.

| Bitrate total | 30 min | 1 h | 2 h |
|---|---|---|---|
| 1,5 Mbps (720p econômico) | ~0,34 GB | ~0,68 GB | ~1,35 GB |
| 2,5 Mbps (720p bom) | ~0,56 GB | ~1,1 GB | ~2,25 GB |
| 4,5 Mbps (1080p) | ~1 GB | ~2 GB | ~4 GB |

## Energia (como medir no teste da Fase 5)

Use um medidor USB (amperímetro USB) entre power bank e cada aparelho e anote W médios.
Autonomia estimada (h) ≈ (capacidade do power bank em Wh × 0,8) ÷ consumo médio em W.
Ex.: power bank 20.000 mAh ≈ 74 Wh → com câmera de 4 W: (74 × 0,8) / 4 ≈ 14 h.

## Boas práticas de bateria e temperatura

- Tela apagada durante a live (o app continua pelo serviço em primeiro plano).
- Libere o app da economia de bateria (botão na tela do app) — algumas marcas matam apps em segundo plano.
- Evite carregar o celular **e** usar hotspot ao sol: é a combinação que mais esquenta.
- O painel mostra a temperatura da bateria e alerta a partir de 42 °C.
