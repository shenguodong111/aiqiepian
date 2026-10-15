#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""
斗鱼实时弹幕采集器（Java 侧通过 ProcessBuilder 调起，与 asr.py 同一套旁路模式）。

为什么用 Python 而不是 Java：danmuproxy 的 8501-8506 端口只接受**静态 RSA 密钥交换**
（TLS_RSA_WITH_AES_256_GCM_SHA384 一类）的 TLS1.2 套件，实测 ECDHE 一律被服务器以
handshake_failure(alert 40) 拒绝；JDK17 的 SunJSSE 已经不再实现任何 TLS_RSA_WITH_* 套件
（supported 列表里 0 个），纯 Java 无法完成握手。Python 走 OpenSSL，可以正常协商。

输出与 B 站采集器完全一致：每行一个 JSON `{"ts": <秒级 epoch>, "uid": <字符串>, "text": "..."}`，
逐条 flush，供 RealAnalyzer 按会话 started_at 折算相对秒数后算热度/高频弹幕。

用法：
  python douyu-danmu.py --room 88660 --out ./media/88660_123_danmaku.jsonl
  python douyu-danmu.py --room 88660 --seconds 45            # 自检：只打印统计
协议细节按《斗鱼直播弹幕协议解析与实现指引》§2 封包 / §3 拆包 / §4 STT / §5 提取 / §6 登录心跳。
"""

import argparse
import json
import os
import socket
import ssl
import struct
import sys
import time

try:
    import websocket          # websocket-client
except ImportError:
    websocket = None          # 启动前由 Java 侧探活；缺失时直接退出并说明原因

MAX_PACKET = 1 << 20          # 单包上限 1 MiB（§3.3 教学实现的资源预算，超了就当协议错误重连）
MAX_PENDING = 2 << 20         # 接收缓冲上限 2 MiB
HEARTBEAT_SEC = 45            # douyudm 2.1.1 约 45 秒一次 mrkl
PORTS = tuple(range(8501, 8507))
HOST = "danmuproxy.douyu.com"
# 服务器只谈静态 RSA 套件；OpenSSL 默认列表里这些是允许的，但 Node/其它客户端默认会排除，
# 这里显式写出来，避免以后换解释器又连不上。
CIPHERS = ("AES256-GCM-SHA384:AES128-GCM-SHA256:AES256-SHA256:AES128-SHA256:"
           "AES256-SHA256:AES128-SHA:AES256-SHA:DES-CBC3-SHA")


# ---------------------------------------------------------------- §2 封包 / §4 STT

def stt_encode(fields):
    """键@=值/ —— 编码时先转义 @ 再转义 /"""
    out = []
    for k, v in fields.items():
        v = str(v).replace("@", "@A").replace("/", "@S")
        out.append(f"{k}@={v}/")
    return "".join(out)


def encode_packet(text, message_type=689):
    """12 字节包头（小端）+ UTF-8 正文 + NUL；length1=length2=完整包长-4=正文长+9"""
    body = text.encode("utf-8")
    n = len(body)
    length = n + 9
    head = struct.pack("<iiHBb", length, length, message_type, 0, 0)
    return head + body + b"\x00"


def stt_decode_records(text):
    """先按原始 '/' 与首个 '@=' 拆字段，再对值内部反转义（先 @S 后 @A）；'//' 分隔多条记录"""
    records = []
    cur = {}
    for seg in text.split("/"):
        if seg == "":
            if cur:
                records.append(cur)
                cur = {}
            continue
        at = seg.find("@=")
        if at < 0:
            continue
        key = seg[:at]
        val = seg[at + 2:].replace("@S", "/").replace("@A", "@")
        if key in cur:
            continue        # 重复键显式不覆盖（§4.2），保留首个值
        cur[key] = val
    if cur:
        records.append(cur)
    return records


class PacketDecoder:
    """§3 流式拆包：每连接一个实例，跨回调保留半包，按字节缓冲后再做 UTF-8 解码"""

    def __init__(self):
        self.pending = bytearray()

    def reset(self):
        self.pending = bytearray()

    def feed(self, data):
        """返回本批解出的 (message_type, body_text) 列表；结构不可信时抛 ProtocolError"""
        if len(data) > MAX_PENDING:
            # 单次载荷本身就超过缓冲上限：即使里面可能都是合法小包，也按§3.3 拒绝并重建连接
            raise ProtocolError(f"单次载荷 {len(data)} 字节超过缓冲上限 {MAX_PENDING}")
        self.pending.extend(data)
        packets = []
        off = 0
        buf = self.pending
        while len(buf) - off >= 4:
            length1 = struct.unpack_from("<i", buf, off)[0]
            total = length1 + 4
            if length1 < 13 or total > MAX_PACKET:
                raise ProtocolError(f"长度不可信 length1={length1}")
            if len(buf) - off < 8:
                break                        # 等下一批（还要读 length2 做校验）
            length2 = struct.unpack_from("<i", buf, off + 4)[0]
            if length1 != length2:
                raise ProtocolError(f"两份长度不一致 {length1} != {length2}")
            if len(buf) - off < total:
                break                        # 半包：保留
            message_type = struct.unpack_from("<H", buf, off + 8)[0]
            encryption = buf[off + 10]
            trailing = buf[off + total - 1]
            body_bytes = bytes(buf[off + 12: off + total - 1])
            off += total
            if encryption != 0:
                # 不支持的加密标志：明确跳过，不按 UTF-8 硬解
                packets.append((message_type, None))
                continue
            if trailing != 0:
                raise ProtocolError("包尾缺少 NUL")
            try:
                body = body_bytes.decode("utf-8")
            except UnicodeDecodeError:
                packets.append((message_type, None))     # 正文无效：边界仍可信，跳过这一包
                continue
            packets.append((message_type, body))
        del buf[:off]
        if len(buf) > MAX_PENDING:
            raise ProtocolError(f"接收缓冲超过 {MAX_PENDING} 字节")
        return packets


class ProtocolError(Exception):
    pass


# ---------------------------------------------------------------- §5 提取聊天

def extract_chat(record, room_id):
    """只处理业务类型 chatmsg；其它业务消息返回 None 交给上层计数"""
    biz = (record.get("type") or "").lower()
    if biz != "chatmsg":
        return None
    txt = (record.get("txt") or "").strip()
    if not txt:
        return None                      # 纯空白聊天忽略
    rid = record.get("rid")
    if rid and str(rid) != str(room_id):
        raise CrossRoom(rid, room_id)    # 有值却不匹配：报告，不跨房间写入
    uid = record.get("uid") or ""
    return {
        "ts": int(time.time()),
        "uid": uid if uid and uid != "0" else "",
        "nick": record.get("nn") or "",
        "text": txt,
        "cid": record.get("cid") or "",
    }


class CrossRoom(Exception):
    pass


# ---------------------------------------------------------------- §7 接线

def tls_context():
    ctx = ssl.create_default_context()      # 绝不关闭证书校验
    ctx.minimum_version = ssl.TLSVersion.TLSv1_2
    ctx.maximum_version = ssl.TLSVersion.TLSv1_2
    try:
        ctx.set_ciphers(CIPHERS)
    except ssl.SSLError:
        pass
    return ctx


def connect(port, timeout=12):
    ws = websocket.create_connection(
        f"wss://{HOST}:{port}/",
        timeout=timeout,
        sslopt={"cert_reqs": ssl.CERT_REQUIRED, "context": tls_context()},
        header={"User-Agent": "Mozilla/5.0 (Windows NT 10.0; Win64; x64) Chrome/126.0.0.0",
                "Origin": "https://www.douyu.com"},
        # 斗鱼正文是二进制包，别按文本解码
        skip_utf8_decode=True,
    )
    return ws


def collect(room_id, out_path=None, seconds=None, on_status=print):
    """主采集循环：断线自动换端口重连，直到被终止或超时"""
    started = time.time()
    counts = {"packets": 0, "chats": 0, "by_type": {}, "reconnects": 0,
              "framing_errors": 0, "cross_room": 0, "users": set()}
    fh = None
    if out_path:
        os.makedirs(os.path.dirname(os.path.abspath(out_path)) or ".", exist_ok=True)
        fh = open(out_path, "a", encoding="utf-8", buffering=1)

    port_idx = 0
    while True:
        if seconds and time.time() - started > seconds:
            break
        port = PORTS[port_idx % len(PORTS)]
        port_idx += 1
        try:
            run_once(room_id, port, fh, counts, started, seconds)
        except (OSError, socket.timeout, websocket.WebSocketException, ssl.SSLError) as e:
            # 只记阶段与类别，不打印正文（§8 日志守则）
            counts["reconnects"] += 1
            on_status(f"[warn] port={port} 连接中断 {type(e).__name__}: {str(e)[:120]}，1.5s 后换端口重连")
            time.sleep(1.5)
        except ProtocolError as e:
            counts["framing_errors"] += 1
            counts["reconnects"] += 1
            on_status(f"[warn] port={port} 协议错误（{e}），重置解析器并重连")
            time.sleep(1.5)
    if fh:
        fh.close()
    return counts


def run_once(room_id, port, fh, counts, started, seconds):
    ws = connect(port)
    try:
        dec = PacketDecoder()              # 每连接独立缓冲（§3.1）
        ws.send(encode_packet(stt_encode({"type": "loginreq", "roomid": room_id})))
        ws.send(encode_packet(stt_encode({"type": "joingroup", "rid": room_id, "gid": "-9999"})))
        last_beat = time.time()
        got_loginres = False
        ws.settimeout(1.0)
        while True:
            if seconds and time.time() - started > seconds:
                break
            if time.time() - last_beat >= HEARTBEAT_SEC:
                ws.send(encode_packet(stt_encode({"type": "mrkl"})))
                last_beat = time.time()
            try:
                frame = ws.recv()
            except (socket.timeout, websocket.WebSocketTimeoutException):
                continue                   # 读空窗不是断线：这一秒没有数据而已
            except websocket.WebSocketConnectionClosedException:
                raise OSError("连接已被对端关闭")
            if frame is None:
                continue
            data = frame if isinstance(frame, bytes) else str(frame).encode("utf-8")
            for message_type, body in dec.feed(data):
                counts["packets"] += 1
                if body is None:
                    continue
                # 690=接收文本包；其它传输类型不当文本业务处理（§7）
                if message_type not in (690, 699, 700, 701, 702, 1000):
                    continue
                for rec in stt_decode_records(body):
                    biz = (rec.get("type") or "").lower()
                    if biz:
                        counts["by_type"][biz] = counts["by_type"].get(biz, 0) + 1
                    if biz == "loginres":
                        got_loginres = True
                        continue
                    if biz == "pingreq":
                        # 服务器要求客户端回报代理探测结果，不回就可能一直不给推聊天
                        resp = {"type": "pongresp"}
                        for k in ("uuid", "clientip", "port", "timestamp"):
                            if k in rec:
                                resp[k] = rec[k]
                        ws.send(encode_packet(stt_encode(resp)))
                        continue
                    if biz != "chatmsg":
                        continue           # mrkl/secretmsg 等交给独立处理器
                    try:
                        chat = extract_chat(rec, room_id)
                    except CrossRoom as e:
                        counts["cross_room"] += 1
                        continue
                    if not chat:
                        continue
                    counts["chats"] += 1
                    if chat["uid"]:
                        counts["users"].add(chat["uid"])
                    if fh:
                        # 落盘只写分析要用的三个字段，昵称留在内存里计数
                        fh.write(json.dumps({"ts": chat["ts"], "uid": chat["uid"],
                                             "text": chat["text"]}, ensure_ascii=False) + "\n")
        # 收尾：残留字节说明被截断，不当聊天输出（§3.3）
        if len(dec.pending) > 0:
            counts["truncated"] = counts.get("truncated", 0) + len(dec.pending)
        if not got_loginres and counts["packets"] == 0:
            raise OSError(f"port={port} 没有任何响应包（登录未完成）")
    finally:
        try:
            ws.send(encode_packet(stt_encode({"type": "logout"})))
        except Exception:
            pass
        try:
            ws.close()
        except Exception:
            pass


def main():
    ap = argparse.ArgumentParser(description="斗鱼弹幕采集（供 AI 切片后端旁路调用）")
    ap.add_argument("--room", required=True, help="斗鱼房间号（纯数字）")
    ap.add_argument("--out", help="jsonl 输出路径；省略则只打印统计")
    ap.add_argument("--seconds", type=int, help="运行多久后自动退出（自检用）")
    ap.add_argument("--ping", action="store_true", help="只验证能否连上并收到聊天，不落盘")
    args = ap.parse_args()

    if args.ping:
        args.out = None
        args.seconds = args.seconds or 30

    if websocket is None:
        print("[RESULT] " + json.dumps({"error": "websocket-client 未安装", "chats": 0}))
        return

    t0 = time.time()
    counts = collect(args.room, args.out, args.seconds)
    counts["users"] = len(counts["users"])
    counts["elapsed_sec"] = round(time.time() - t0, 1)
    print("[RESULT] " + json.dumps(counts, ensure_ascii=False))


if __name__ == "__main__":
    main()
