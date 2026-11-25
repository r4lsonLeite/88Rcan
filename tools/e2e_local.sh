#!/usr/bin/env bash
# Teste de ponta a ponta 100% local (sem câmera e sem YouTube):
#
#   ffmpeg (câmera simulada) --RTP--> fake_rtsp_camera.py --RTSP/Digest--> 88Rcan core
#        --RTMP--> ffmpeg (YouTube simulado) --> saida.mkv --> ffprobe
#
# Perfis (variável PROFILE):
#   h264   (padrão) 720p30 H.264 + AAC
#   yoosee 1920x2160 15fps H.265 + G.711 A-law 16 kHz — igual às câmeras Yoosee de lente dupla
#
# Requisitos: ffmpeg 6.1+/ffprobe com libx264/libx265, python3, JDK 17+.
set -euo pipefail

ROOT="$(cd "$(dirname "$0")/.." && pwd)"
WORK="${WORK:-$(mktemp -d)}"
DURATION="${DURATION:-20}"
PROFILE="${PROFILE:-h264}"
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

# 2) Fonte de vídeo/áudio da câmera
if [ "$PROFILE" = "yoosee" ]; then
  W=1920; H=2160; FPS=15; VCODEC=hevc; ACODEC=none
  VENC=(-c:v libx265 -preset veryfast -x265-params "log-level=error:keyint=30:bframes=0")
  AENC=(-c:a pcm_alaw -ar 16000 -ac 1)
else
  W=1280; H=720; FPS=30; VCODEC=h264; ACODEC=aac
  VENC=(-c:v libx264 -preset veryfast -profile:v main -bf 0 -g 60)
  AENC=(-c:a aac -b:a 128k -ar 48000)
fi
ffmpeg -v error -re -f lavfi -i "testsrc=size=${W}x${H}:rate=$FPS" -f lavfi -i "sine=frequency=440:sample_rate=48000" \
  -map 0:v "${VENC[@]}" -pix_fmt yuv420p -b:v 2500k -f rtp "rtp://127.0.0.1:5004" \
  -map 1:a "${AENC[@]}" -f rtp "rtp://127.0.0.1:5006" \
  -sdp_file "$WORK/cam.sdp" > "$WORK/source.log" 2>&1 &
PIDS+=($!)

# 3) "YouTube" local: ffmpeg escutando RTMP e gravando o que chega
ffmpeg -v error -y -listen 1 -i "rtmp://127.0.0.1:$RTMP_PORT/live2/chave-teste" -c copy -f matroska "$WORK/saida.mkv" > "$WORK/server.log" 2>&1 &
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
  -of default=noprint_wrappers=1 "$WORK/saida.mkv" | tee "$WORK/probe.txt"
FRAMES=$(ffprobe -v error -select_streams v -count_packets -show_entries stream=nb_read_packets -of csv=p=0 "$WORK/saida.mkv")
echo "frames de vídeo: $FRAMES"
ERRS=$(ffmpeg -v error -i "$WORK/saida.mkv" -f null - 2>&1 | wc -l)
echo "erros de decodificação: $ERRS"

AUDIO_OK=true
if [ "$ACODEC" != none ]; then grep -q "codec_name=$ACODEC" "$WORK/probe.txt" || AUDIO_OK=false; fi
grep -q "codec_name=$VCODEC" "$WORK/probe.txt" && grep -q "width=$W" "$WORK/probe.txt" && grep -q "height=$H" "$WORK/probe.txt" \
  && $AUDIO_OK && [ "$FRAMES" -gt $(( (DURATION - 6) * FPS )) ] && [ "$ERRS" -eq 0 ] \
  && echo "OK [$PROFILE]: pipeline câmera → app → RTMP funcionando" || { echo "FALHOU [$PROFILE]"; exit 1; }
