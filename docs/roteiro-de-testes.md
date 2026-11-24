# Roteiro de desenvolvimento e testes — até a primeira live

Cada fase tem um **critério de saída**: só avance quando ele for atingido.

| # | Etapa | Status |
|---|---|---|
| 1 | Definir arquitetura (ponte sem recodificação) | ✅ [arquitetura.md](arquitetura.md) |
| 2 | Definir requisitos de câmera | ✅ [cameras-compativeis.md](cameras-compativeis.md) |
| 3 | Listar equipamentos | ✅ [equipamentos.md](equipamentos.md) |
| 4 | Motor RTSP → RTMP (`core`) | ✅ implementado, testes unitários + ponta a ponta |
| 5 | Validar com câmera real no PC | ⏳ Fase 1 |
| 6 | Validar no YouTube a partir do PC | ⏳ Fase 2 |
| 7 | App Android (serviço, rede, energia) | ✅ código inicial — compilar no Android Studio |
| 8 | Interface de operação | ✅ código inicial — validar em campo (Fase 3) |
| 9 | Testar com internet móvel | ⏳ Fase 4 |
| 10 | Otimizar custo, bateria e estabilidade | ⏳ Fase 5 |

---

## Fase 0 — Bancada sem hardware (já automatizada)

```bash
cd core && ./gradlew test       # testes unitários (SDP, RTP, H.264, AAC, AMF0, Digest, SPS)
../tools/e2e_local.sh           # câmera simulada → motor → servidor RTMP local → ffprobe
```
**Saída:** `OK: pipeline câmera → app → RTMP funcionando` (H.264 720p + AAC, 0 erros de decodificação).

## Fase 1 — Câmera real no PC

1. Ligue a câmera no roteador (ou no hotspot do celular) e descubra o IP.
2. Configure na câmera: **H.264, 720p, 30 fps, GOP 60, CBR 2000 kbps, áudio AAC**.
3. Rode `ffprobe` conforme [cameras-compativeis.md](cameras-compativeis.md#como-validar-uma-câmera-em-5-minutos-antes-de-ir-a-campo).
4. Grave 2 minutos pelo motor, num servidor RTMP local:
   ```bash
   ffmpeg -listen 1 -i rtmp://127.0.0.1:1935/live/teste -c copy teste.flv &
   core/build/install/core/bin/core --source "rtsp://..." --target rtmp://127.0.0.1:1935/live/teste --duration 120
   ffplay teste.flv
   ```
**Saída:** imagem e som corretos, sem artefatos; desligar/religar a câmera no meio → o motor reconecta sozinho.

## Fase 2 — YouTube a partir do PC

1. YouTube Studio → **Transmitir ao vivo** → Stream → copie a **chave**. Visibilidade **Não listado**.
   (A conta precisa ter a transmissão ao vivo habilitada — a liberação pode levar até 24 h na primeira vez.)
2. ```bash
   core/build/install/core/bin/core --source "rtsp://..." \
       --target "rtmps://a.rtmps.youtube.com/live2/SUA-CHAVE" --duration 600
   ```
3. No Studio, confira "Saúde do stream".

**Saída:** 10 min sem alertas graves no Studio; latência e imagem aceitáveis.
Alerta comum: *"intervalo de keyframe"* → ajuste o GOP da câmera para 2 s.

## Fase 3 — App no celular, Wi-Fi

1. Abra `android/` no Android Studio, conecte o celular (depuração USB) e rode o app.
2. Topologia A (câmera no hotspot do celular) ou B (celular no Wi-Fi da câmera + opção de redes separadas).
3. Preencha URL da câmera, chave, e toque **Iniciar live**. Apague a tela.

**Saída:** 30 min de live com tela apagada; notificação atualizando; botão Parar da notificação funciona.

## Fase 4 — Internet móvel (etapa 9)

Faça cada teste por 15 min e registre na tabela:

| Cenário | Bitrate da câmera | kbps médio no app | Descartados | Reconexões | Saúde no Studio |
|---|---|---|---|---|---|
| 4G sinal bom, parado | 2000 | | | | |
| 4G sinal médio | 2000 | | | | |
| 4G sinal médio | 1200 (sub-fluxo) | | | | |
| Topologia B (Wi-Fi da câmera + 4G) | 2000 | | | | |
| Modo avião 20 s no meio da live | 2000 | | | | |
| Trocar de antena (andar/veículo) | 1500 | | | | |

**Saída:** com o bitrate escolhido, `descartados` estável (não crescendo continuamente) e
recuperação automática após perda total de sinal.

## Fase 5 — Custo, bateria e estabilidade (etapa 10)

- **Bateria:** live de 2 h com power bank; anote % e temperatura a cada 15 min (o painel mostra).
- **Energia:** meça W da câmera e do celular com medidor USB; calcule a autonomia.
- **Estabilidade:** live de 4 h contínua. Verifique reconexões e se o app foi morto pelo sistema
  (se foi: liberar da economia de bateria; algumas marcas têm "inicialização automática"/"bloqueio de apps" próprios).
- **Custo:** dados consumidos (Configurações → Uso de dados) vs. tabela de [equipamentos.md](equipamentos.md).

**Saída:** sabemos quantas horas o kit aguenta, quanto custa por hora de live e qual bitrate usar em cada situação.

## Fase 6 — Primeira live pública

Checklist no local:

- [ ] Teste de velocidade de **upload** no ponto exato da transmissão
- [ ] Bitrate da câmera ≤ 70 % do upload medido
- [ ] Celular e câmera carregados + power banks
- [ ] Live criada no YouTube Studio 15 min antes (título, miniatura, visibilidade)
- [ ] Iniciar no app → conferir "AO VIVO" e a prévia no Studio → só então "Iniciar transmissão" no Studio
- [ ] Tela apagada, celular na sombra, com sinal bom
- [ ] Ao final: encerrar no Studio e depois tocar **Parar** no app

---

## Ferramentas de diagnóstico rápidas

| Sintoma | Onde olhar | Ação |
|---|---|---|
| "Codec de vídeo 'H265' não suportado" | log do app | trocar câmera para H.264 |
| "Usuário ou senha da câmera inválidos" | log | criar/confirmar conta RTSP |
| "Servidor recusou a publicação" | log | chave errada/expirada; gerar nova no Studio |
| Reconexões frequentes | painel | sinal fraco → reduzir bitrate / sub-fluxo / reposicionar celular |
| `descartados` subindo sem parar | painel | upload insuficiente → reduzir bitrate na câmera |
| App morre com tela apagada | — | liberar economia de bateria; desativar "otimização" do fabricante |
