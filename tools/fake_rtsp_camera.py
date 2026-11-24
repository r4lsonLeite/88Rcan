#!/usr/bin/env python3
"""
Câmera IP falsa para testes de bancada.

Recebe RTP/UDP gerado pelo ffmpeg (vídeo H.264 + áudio AAC) e o entrega a clientes RTSP
usando RTP intercalado no TCP — exatamente como o app conversa com uma câmera real.
Exige autenticação Digest (usuário/senha configuráveis) para exercitar esse caminho.

Uso: ver tools/e2e_local.sh
"""
import argparse
import hashlib
import os
import socket
import struct
import threading
import time

parser = argparse.ArgumentParser()
parser.add_argument("--port", type=int, default=8554)
parser.add_argument("--sdp", required=True, help="arquivo SDP gerado pelo ffmpeg (-sdp_file)")
parser.add_argument("--video-port", type=int, default=5004)
parser.add_argument("--audio-port", type=int, default=5006)
parser.add_argument("--user", default="admin")
parser.add_argument("--password", default="segredo")
args = parser.parse_args()

REALM = "FakeCam"
NONCE = hashlib.md5(str(time.time()).encode()).hexdigest()
clients = []  # (sock, {udp_port: channel}, lock)
clients_lock = threading.Lock()


def md5(s):
    return hashlib.md5(s.encode()).hexdigest()


def load_sdp():
    while not os.path.exists(args.sdp) or os.path.getsize(args.sdp) == 0:
        time.sleep(0.1)
    time.sleep(0.2)
    lines = open(args.sdp).read().replace("\r\n", "\n").strip().split("\n")
    out, track = [], 0
    for line in lines:
        out.append(line)
        if line.startswith("m="):
            out.append(f"a=control:trackID={track}")
            track += 1
    return "\r\n".join(out) + "\r\n"


def udp_forwarder(port, kind):
    s = socket.socket(socket.AF_INET, socket.SOCK_DGRAM)
    s.setsockopt(socket.SOL_SOCKET, socket.SO_RCVBUF, 4 * 1024 * 1024)
    s.bind(("127.0.0.1", port))
    while True:
        data, _ = s.recvfrom(65536)
        with clients_lock:
            targets = list(clients)
        for sock, channels, lock in targets:
            ch = channels.get(kind)
            if ch is None:
                continue
            try:
                with lock:
                    sock.sendall(b"$" + bytes([ch]) + struct.pack(">H", len(data)) + data)
            except OSError:
                pass


def check_auth(method, headers):
    h = headers.get("authorization", "")
    if not h.startswith("Digest "):
        return False
    params = {}
    for part in h[7:].split(","):
        if "=" in part:
            k, v = part.strip().split("=", 1)
            params[k.strip()] = v.strip().strip('"')
    ha1 = md5(f"{args.user}:{REALM}:{args.password}")
    ha2 = md5(f"{method}:{params.get('uri', '')}")
    if "qop" in params:
        expected = md5(f"{ha1}:{params['nonce']}:{params['nc']}:{params['cnonce']}:{params['qop']}:{ha2}")
    else:
        expected = md5(f"{ha1}:{NONCE}:{ha2}")
    return params.get("response") == expected


def handle(conn):
    sdp = load_sdp()
    f = conn.makefile("rb")
    lock = threading.Lock()
    channels = {}
    session = "12345678"
    registered = False
    try:
        while True:
            line = f.readline()
            if not line:
                break
            if line in (b"\r\n", b"\n"):
                continue
            method, uri, _ = line.decode().split(" ", 2)
            headers = {}
            while True:
                h = f.readline().decode().strip()
                if not h:
                    break
                k, v = h.split(":", 1)
                headers[k.strip().lower()] = v.strip()
            cseq = headers.get("cseq", "0")
            extra, body, status = [], b"", "200 OK"
            if method != "OPTIONS" and not check_auth(method, headers):
                status = "401 Unauthorized"
                extra.append(f'WWW-Authenticate: Digest realm="{REALM}", nonce="{NONCE}"')
            elif method == "OPTIONS":
                extra.append("Public: OPTIONS, DESCRIBE, SETUP, PLAY, TEARDOWN, GET_PARAMETER")
            elif method == "DESCRIBE":
                body = sdp.encode()
                base = uri if uri.endswith("/") else uri + "/"
                extra += ["Content-Type: application/sdp", f"Content-Base: {base}"]
            elif method == "SETUP":
                transport = headers.get("transport", "")
                il = transport.split("interleaved=")[1].split(";")[0].split("-")[0]
                kind = "video" if "trackID=0" in uri else "audio"
                channels[kind] = int(il)
                extra += [f"Transport: RTP/AVP/TCP;unicast;interleaved={il}-{int(il) + 1}",
                          f"Session: {session};timeout=60"]
            elif method == "PLAY":
                extra.append(f"Session: {session}")
            elif method == "TEARDOWN":
                break
            resp = f"RTSP/1.0 {status}\r\nCSeq: {cseq}\r\n" + "".join(e + "\r\n" for e in extra)
            if body:
                resp += f"Content-Length: {len(body)}\r\n"
            resp = resp.encode() + b"\r\n" + body
            with lock:
                conn.sendall(resp)
            if method == "PLAY" and status.startswith("200") and not registered:
                registered = True
                with clients_lock:
                    clients.append((conn, channels, lock))
                print(f"[fake-cam] cliente reproduzindo {channels}", flush=True)
    except (OSError, ValueError, IndexError) as e:
        print(f"[fake-cam] cliente saiu: {e}", flush=True)
    finally:
        with clients_lock:
            clients[:] = [c for c in clients if c[0] is not conn]
        conn.close()


threading.Thread(target=udp_forwarder, args=(args.video_port, "video"), daemon=True).start()
threading.Thread(target=udp_forwarder, args=(args.audio_port, "audio"), daemon=True).start()
srv = socket.socket()
srv.setsockopt(socket.SOL_SOCKET, socket.SO_REUSEADDR, 1)
srv.bind(("127.0.0.1", args.port))
srv.listen(4)
print(f"[fake-cam] rtsp://{args.user}:{args.password}@127.0.0.1:{args.port}/stream1", flush=True)
while True:
    c, _ = srv.accept()
    threading.Thread(target=handle, args=(c,), daemon=True).start()
