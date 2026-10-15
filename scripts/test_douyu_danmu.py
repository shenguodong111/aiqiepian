#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""斗鱼协议核心离线测试（不访问直播间）：封包/拆包/STT/聊天提取。

对应《斗鱼直播弹幕协议解析与实现指引》§8 的验收清单：中文与 emoji 的字节长度、转义与反转义顺序、
所有拆分位置、连续多包、尾部半包、长度不一致、过小/超大长度、缓冲上限、非明文标志、缺 NUL、
无效 UTF-8、STT 数组记录、重复键、大小写业务类型、匿名用户、纯空白聊天、跨房间 rid。

运行：python scripts/test_douyu_danmu.py
"""

import importlib.util
import os
import struct
import unittest

HERE = os.path.dirname(os.path.abspath(__file__))
spec = importlib.util.spec_from_file_location("douyu_danmu", os.path.join(HERE, "douyu-danmu.py"))
dd = importlib.util.module_from_spec(spec)
spec.loader.exec_module(dd)


def raw(body_text):
    """构造一个合法的接收包（690 明文），用于喂给拆包器"""
    return dd.encode_packet(body_text, message_type=690)


class TestPacketEncoding(unittest.TestCase):

    def test_heartbeat_fixture(self):
        """§2.2 可手工核对的心跳包夹具，逐字节比对"""
        want = ("14 00 00 00 14 00 00 00 B1 02 00 00 "
                "74 79 70 65 40 3D 6D 72 6B 6C 2F 00")
        self.assertEqual(" ".join(f"{b:02X}" for b in dd.encode_packet("type@=mrkl/")), want)

    def test_length_is_bytes_not_chars(self):
        """正文含中文/emoji：length 必须是 UTF-8 字节数，不是字符数"""
        text = "type@=chatmsg/txt@=你好👍/"
        pkt = dd.encode_packet(text)
        n = len(text.encode("utf-8"))
        self.assertEqual(len(pkt), n + 13)
        self.assertEqual(struct.unpack_from("<i", pkt, 0)[0], n + 9)
        self.assertEqual(struct.unpack_from("<i", pkt, 4)[0], n + 9)
        self.assertNotEqual(n, len(text))          # 字符数与字节数确实不同

    def test_header_fields(self):
        pkt = dd.encode_packet("type@=mrkl/")
        self.assertEqual(struct.unpack_from("<H", pkt, 8)[0], 689)
        self.assertEqual(pkt[10], 0)              # encryption
        self.assertEqual(pkt[-1], 0)              # NUL

    def test_stt_encode_order(self):
        """§4.1 编码：先转义 @ 再转义 /"""
        self.assertEqual(dd.stt_encode({"txt": "A/B@C"}), "txt@=A@SB@AC/")
        self.assertEqual(dd.stt_encode({"txt": "@S"}), "txt@=@AS/")


class TestSttDecoding(unittest.TestCase):

    def test_decode_escapes(self):
        recs = dd.stt_decode_records("txt@=A@SB@AC/")
        self.assertEqual(recs[0]["txt"], "A/B@C")

    def test_literal_at_s_not_double_unescaped(self):
        """原文 @S 编码成 @AS：必须先恢复 @S 再恢复 @A，否则 @S 会被错当斜杠"""
        self.assertEqual(dd.stt_decode_records("txt@=@AS/")[0]["txt"], "@S")

    def test_structure_split_before_unescape(self):
        """值里编码过的斜杠不能被当成字段分隔符"""
        rec = dd.stt_decode_records("type@=chatmsg/txt@=a@Sb/uid@=9/")
        self.assertEqual(rec[0]["txt"], "a/b")
        self.assertEqual(rec[0]["uid"], "9")

    def test_array_records(self):
        """§4.3 // 分隔多条记录，不能假定永远是单个对象"""
        recs = dd.stt_decode_records("type@=chatmsg/txt@=第一条//type@=chatmsg/txt@=第二条//")
        self.assertEqual(len(recs), 2)
        self.assertEqual([r["txt"] for r in recs], ["第一条", "第二条"])

    def test_duplicate_key_keeps_first(self):
        rec = dd.stt_decode_records("uid@=1/uid@=2/")[0]
        self.assertEqual(rec["uid"], "1")

    def test_big_uid_stays_string(self):
        rec = dd.stt_decode_records("uid@=9007199254740993/")[0]
        self.assertIsInstance(rec["uid"], str)
        self.assertEqual(rec["uid"], "9007199254740993")

    def test_empty_value_kept(self):
        rec = dd.stt_decode_records("cid@=/uid@=7/")[0]
        self.assertEqual(rec["cid"], "")


class TestStreamFraming(unittest.TestCase):

    def setUp(self):
        self.d = dd.PacketDecoder()

    def test_whole_packet(self):
        pkt = raw("type@=chatmsg/txt@=hi/")
        out = self.d.feed(pkt)
        self.assertEqual(len(out), 1)
        self.assertEqual(out[0][0], 690)
        self.assertIn("chatmsg", out[0][1])

    def test_every_split_position(self):
        """任意位置切成两批必须得到同一个结果"""
        pkt = raw("type@=chatmsg/txt@=你好中文emoji👍/uid@=42/")
        for i in range(1, len(pkt)):
            d = dd.PacketDecoder()
            a = d.feed(pkt[:i])
            b = d.feed(pkt[i:])
            self.assertEqual(len(a) + len(b), 1, f"split at {i} 产出 {len(a)}+{len(b)} 包")

    def test_byte_by_byte(self):
        pkt = raw("type@=mrkl/")
        got = []
        for i in range(len(pkt)):
            got.extend(self.d.feed(pkt[i:i + 1]))
        self.assertEqual(len(got), 1)

    def test_chinese_char_split_across_reads(self):
        """一个汉字的 3 字节被切在两次输入之间：先拼字节再解码，不能出现替换字符"""
        pkt = raw("type@=chatmsg/txt@=弹幕/")
        cut = len(pkt) - 4                       # 落在最后一个汉字的字节中间
        self.d.feed(pkt[:cut])
        out = self.d.feed(pkt[cut:])
        self.assertEqual(out[0][1], "type@=chatmsg/txt@=弹幕/")
        self.assertNotIn("\ufffd", out[0][1])

    def test_multiple_packets_in_one_payload(self):
        blob = raw("type@=chatmsg/txt@=一/") + raw("type@=chatmsg/txt@=二/") + raw("type@=mrkl/")[:9]
        out = self.d.feed(blob)
        self.assertEqual(len(out), 2)            # 第三个是半包，不交付
        self.assertEqual(len(self.d.pending), 9)

    def test_length_mismatch_is_protocol_error(self):
        pkt = bytearray(raw("type@=mrkl/"))
        struct.pack_into("<i", pkt, 4, 999)      # 篡改 length2
        with self.assertRaises(dd.ProtocolError):
            self.d.feed(bytes(pkt))

    def test_too_small_length(self):
        pkt = bytearray(raw("type@=mrkl/"))
        struct.pack_into("<i", pkt, 0, 5)
        struct.pack_into("<i", pkt, 4, 5)
        with self.assertRaises(dd.ProtocolError):
            self.d.feed(bytes(pkt))

    def test_malicious_huge_length(self):
        body = b"x"
        head = struct.pack("<iiHBb", 1 << 30, 1 << 30, 690, 0, 0)
        with self.assertRaises(dd.ProtocolError):
            self.d.feed(head + body + b"\x00")

    def test_pending_buffer_cap(self):
        """合法小包堆到超过缓冲上限也要拒绝（§3.3）"""
        one = raw("type@=mrkl/")
        blob = one * (dd.MAX_PENDING // len(one) + 2)
        with self.assertRaises(dd.ProtocolError):
            self.d.feed(blob)

    def test_encryption_flag_not_decoded_as_text(self):
        pkt = bytearray(raw("type@=mrkl/"))
        pkt[10] = 1                              # encryption != 0
        out = self.d.feed(bytes(pkt))
        self.assertEqual(out[0][1], None)        # 明确不支持，不按 UTF-8 硬解

    def test_missing_trailing_nul(self):
        body = b"type@=mrkl/"
        n = len(body)
        pkt = struct.pack("<iiHBb", n + 9, n + 9, 690, 0, 0) + body + b"X"
        with self.assertRaises(dd.ProtocolError):
            self.d.feed(pkt)

    def test_invalid_utf8_body_is_skipped(self):
        body = b"\xff\xfe invalid"
        n = len(body)
        pkt = struct.pack("<iiHBb", n + 9, n + 9, 690, 0, 0) + body + b"\x00"
        out = self.d.feed(pkt)                   # 边界可信，正文无效 → 跳过不抛
        self.assertEqual(out[0][1], None)

    def test_reset_clears_partial(self):
        self.d.feed(raw("type@=mrkl/")[:7])
        self.assertTrue(self.d.pending)
        self.d.reset()
        self.assertEqual(len(self.d.pending), 0)


class TestChatExtraction(unittest.TestCase):

    def test_chat_fields(self):
        rec = dd.stt_decode_records(
            "type@=chatmsg/rid@=100/uid@=7/nn@=张三/txt@=你好啊/cid@=55/")[0]
        chat = dd.extract_chat(rec, "100")
        self.assertEqual(chat["text"], "你好啊")
        self.assertEqual(chat["nick"], "张三")
        self.assertEqual(chat["uid"], "7")
        self.assertEqual(chat["cid"], "55")
        self.assertIsInstance(chat["ts"], int)

    def test_type_case_insensitive(self):
        rec = dd.stt_decode_records("type@=ChatMsg/txt@=hi/")[0]
        self.assertIsNotNone(dd.extract_chat(rec, None))

    def test_non_chat_types_return_none(self):
        for t in ("mrkl", "loginres", "sendmsg", "bang"):
            self.assertIsNone(dd.extract_chat(dd.stt_decode_records(f"type@={t}/txt@=x/")[0], "1"))

    def test_blank_txt_ignored(self):
        self.assertIsNone(dd.extract_chat(dd.stt_decode_records("type@=chatmsg/txt@=   /")[0], "1"))

    def test_anonymous_uid(self):
        for uid in ("", "0"):
            chat = dd.extract_chat(dd.stt_decode_records(f"type@=chatmsg/txt@=x/uid@={uid}/")[0], "1")
            self.assertEqual(chat["uid"], "")

    def test_rid_mismatch_raises(self):
        rec = dd.stt_decode_records("type@=chatmsg/rid@=200/txt@=串台了/")[0]
        with self.assertRaises(dd.CrossRoom):
            dd.extract_chat(rec, "100")

    def test_missing_rid_filled_by_connection_context(self):
        rec = dd.stt_decode_records("type@=chatmsg/txt@=没有rid/")[0]
        self.assertIsNotNone(dd.extract_chat(rec, "100"))

    def test_extra_unknown_fields_ignored(self):
        rec = dd.stt_decode_records("type@=chatmsg/txt@=ok/uid@=1/newfld@=?/")[0]
        self.assertEqual(dd.extract_chat(rec, None)["text"], "ok")

    def test_pongresp_echoes_probe_fields(self):
        """pingreq 要带原字段回 pongresp（§6 之外的服务器额外要求）"""
        rec = dd.stt_decode_records(
            "type@=pingreq/uuid@=abc/clientip@=1.2.3.4/port@=8501/timestamp@=1700000000/")[0]
        out = {}
        for k in ("uuid", "clientip", "port", "timestamp"):
            if k in rec:
                out[k] = rec[k]
        text = dd.stt_encode(dict({"type": "pongresp"}, **out))
        self.assertIn("uuid@=abc/", text)
        self.assertIn("timestamp@=1700000000/", text)


if __name__ == "__main__":
    unittest.main(verbosity=2)
