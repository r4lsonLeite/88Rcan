#!/usr/bin/env bash
# Teste de ponta a ponta 100% local (sem câmera e sem YouTube):
#
#   ffmpeg (câmera simulada) --RTP--> fake_rtsp_camera.py --RTSP/Digest--> 88Rcan core
#        --RTMP--> ffmpeg (YouTube simulado) --> saida.flv --> ffprobe
#
# Requisitos: ffmpeg/ffprobe com libx264, python3, JDK 17+.
set -euo pipefail

ROOT="$(cd "$(dirname "$0")/.." && pwd)"
WORK="${WORK:-$(mktemp -d)}"
DURATION="${DURATION:-20}"
RTSP_PORT=8554
RTMP_PORT=19350
PIDS=()
cleanup() { for p in "${PIDS[@]}"; do kill "$p" 2>/dev/null || true; done; }
trap cleanup EXIT

echo ">> pasta de trabalho: $WORK"
cd "$ROOT/core"
./gradlew -q installDist

# 1) Câmera falsa (servidor RTSP)
python3 "$ROOT/tools/fake_rtsp_camera.py" --port $RTSP_PORT --sdp "$WORK/cam.sdp" > "$WORK/cam.log" 2>&1 &
PIDS+=($!)
sleep 1

# 2) Fonte de vídeo/áudio da câmera: 720p30 H.264 (GOP 2s, sem B-frames) + AAC 48 kHz
ffmpeg -v error -re -f lavfi -i "testsrc=size=1280x720:rate=30" -f lavfi -i "sine=frequency=440:sample_rate=48000" \
  -map 0:v -c:v libx264 -preset veryfast -pix_fmt yuv420p -profile:v main -bf 0 -g 60 -b:v 2500k -f rtp "rtp://127.0.0.1:5004" \
  -map 1:a -c:a aac -b:a 128k -f rtp "rtp://127.0.0.1:5006" \
  -sdp_file "$WORK/cam.sdp" > "$WORK/source.log" 2>&1 &
PIDS+=($!)

# 3) "YouTube" local: ffmpeg escutando RTMP e gravando o que chega
ffmpeg -v error -y -listen 1 -i "rtmp://127.0.0.1:$RTMP_PORT/live2/chave-teste" -c copy -f flv "$WORK/saida.flv" > "$WORK/server.log" 2>&1 &
SERVER=$!
PIDS+=($SERVER)
sleep 1

# 4) O motor do app
"$ROOT/core/build/install/core/bin/core" \
  --source "rtsp://127.0.0.1:$RTSP_PORT/stream1" --user admin --password segredo \
  --target "rtmp://127.0.0.1:$RTMP_PORT/live2/chave-teste" --duration "$DURATION" | tee "$WORK/relay.log"

wait $SERVER || true

echo
echo ">> Resultado recebido pelo servidor RTMP:"
ffprobe -v error -show_entries stream=codec_name,width,height,sample_rate,channels:format=duration \
  -of default=noprint_wrappers=1 "$WORK/saida.flv" | tee "$WORK/probe.txt"
FRAMES=$(ffprobe -v error -select_streams v -count_packets -show_entries stream=nb_read_packets -of csv=p=0 "$WORK/saida.flv")
echo "frames de vídeo: $FRAMES"
ERRS=$(ffmpeg -v error -i "$WORK/saida.flv" -f null - 2>&1 | wc -l)
echo "erros de decodificação: $ERRS"

grep -q "codec_name=h264" "$WORK/probe.txt" && grep -q "width=1280" "$WORK/probe.txt" \
  && grep -q "codec_name=aac" "$WORK/probe.txt" && [ "$FRAMES" -gt $(( (DURATION - 6) * 30 )) ] && [ "$ERRS" -eq 0 ] \
  && echo "OK: pipeline câmera → app → RTMP funcionando" || { echo "FALHOU"; exit 1; }
