#!/usr/bin/env python3
"""Writes TL byte fixtures for unit tests, serialized by an independent Python serializer that follows
schema.tl (so they cross-check the Kotlin generator). Run from the repo root:
    python3 tools/tlgen/fixtures.py
"""
import os
import re
import struct
import sys

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
import tlgen

CTORS, FNS = tlgen.parse(os.path.join(tlgen.ROOT, "schema.tl"))
BY_NAME = {c.name: c for c in CTORS + FNS}
OUT = os.path.join(tlgen.REPO, "app/src/test/resources/tl")


def tl_bytes(b):
    if isinstance(b, str):
        b = b.encode()
    if len(b) < 254:
        out = bytes([len(b)]) + b
    else:
        out = bytes([254]) + len(b).to_bytes(3, "little") + b
    return out + b"\0" * ((-len(out)) % 4)


def ser_value(t, v):
    t = t.lstrip("%")
    m = re.match(r"^[Vv]ector<(.+)>$", t)
    if m:
        return struct.pack("<ii", 0x1cb5c415, len(v)) + b"".join(ser_value(m.group(1), x) for x in v)
    if t in ("int", "Int"):
        return struct.pack("<i", v)
    if t in ("long", "Long"):
        return struct.pack("<q", v)
    if t in ("double", "Double"):
        return struct.pack("<d", v)
    if t in ("string", "bytes", "String", "Bytes"):
        return tl_bytes(v)
    # boxed object: (ctor_name, {fields})
    name, fields = v
    return ser_ctor(BY_NAME[name], fields)


def ser_ctor(c, fields):
    out = struct.pack("<I", c.cid)
    flag_vals = {}
    for p in c.params:
        if p.flag_field and (p.name in fields) and fields[p.name] not in (None, False):
            flag_vals[p.flag_field] = flag_vals.get(p.flag_field, 0) | (1 << p.bit)
    for p in c.params:
        if p.is_flags:
            out += struct.pack("<i", flag_vals.get(p.name, 0) - (1 << 32) if flag_vals.get(p.name, 0) >= 1 << 31 else flag_vals.get(p.name, 0))
        elif p.flag_field:
            if p.type == "true" or p.name not in fields or fields[p.name] in (None, False):
                continue
            out += ser_value(p.type, fields[p.name])
        else:
            out += ser_value(p.type, fields[p.name])
    return out


def peer_user(uid):
    return ("peerUser", {"user_id": uid})


def doc_video(doc_id=1234567890123, name="movie.mp4"):
    return ("messageMediaDocument", {"video": True, "document": ("document", {
        "id": doc_id, "access_hash": 987654321, "file_reference": b"\x01\x02\x03\x04\x05", "date": 1700000000,
        "mime_type": "video/mp4", "size": 3 * 1024 * 1024 * 1024,
        "thumbs": [("photoSize", {"type": "m", "w": 320, "h": 180, "size": 12000})],
        "dc_id": 4,
        "attributes": [("documentAttributeVideo", {"duration": 5400.5, "w": 1920, "h": 1080, "supports_streaming": True}),
                       ("documentAttributeFilename", {"file_name": name})]})})


def message(mid, uid, text, media=None):
    f = {"id": mid, "peer_id": peer_user(uid), "from_id": peer_user(uid), "date": 1700000000 + mid, "message": text}
    if media:
        f["media"] = media
    return ("message", f)


def difference(messages):
    state = ("updates.state", {"pts": 10, "qts": 0, "date": 1700000100, "seq": 5, "unread_count": 0})
    user = ("user", {"id": 777, "first_name": "Tester", "access_hash": 5})
    return ser_ctor(BY_NAME["updates.difference"], {
        "new_messages": messages, "new_encrypted_messages": [], "other_updates": [], "chats": [], "users": [user], "state": state})


def callback_query(qid=9001, uid=777, msg_id=42, data=b"v1:AbCd1234:pp"):
    return ("updateBotCallbackQuery", {"query_id": qid, "user_id": uid, "peer": peer_user(uid), "msg_id": msg_id,
                                       "chat_instance": 5, "data": data})


def remote_fixtures():
    w = lambda name, b: open(os.path.join(OUT, name), "wb").write(b)
    user = ("user", {"id": 777, "first_name": "Tester", "access_hash": 5})
    min_user = ("user", {"id": 888, "min": True, "access_hash": 6})
    w("push_short_callback.bin", ser_ctor(BY_NAME["updateShort"], {"update": callback_query(), "date": 1700000000}))
    w("push_updates.bin", ser_ctor(BY_NAME["updates"], {
        "updates": [callback_query(qid=1, data=b"v1:AbCd1234:nx"), ("updateDeleteMessages", {"messages": [1], "pts": 1, "pts_count": 1})],
        "users": [user, min_user], "chats": [], "date": 1700000000, "seq": 3}))
    w("push_unrelated.bin", ser_ctor(BY_NAME["updateShort"], {
        "update": ("updateDeleteMessages", {"messages": [1, 2], "pts": 1, "pts_count": 2}), "date": 1700000000}))
    markup = ("replyInlineMarkup", {"rows": [("keyboardInlineButtonRow", {"buttons": [
        ("keyboardInlineButton", {"text": "\u23ef", "type": ("inlineButtonTypeCallback", {"data": b"v1:AbCd1234:pp"})})]})]})
    peer = ("inputPeerUser", {"user_id": 777, "access_hash": 5})
    w("req_send_message.bin", ser_ctor(BY_NAME["messages.sendMessage"], {"peer": peer, "message": "hi", "random_id": 42, "reply_markup": markup}))
    w("req_edit_message.bin", ser_ctor(BY_NAME["messages.editMessage"], {"peer": peer, "id": 7, "message": "bye", "reply_markup": markup}))
    w("req_pin_message.bin", ser_ctor(BY_NAME["messages.updatePinnedMessage"], {"silent": True, "peer": peer, "id": 7}))
    w("req_answer_callback.bin", ser_ctor(BY_NAME["messages.setBotCallbackAnswer"], {"query_id": 9001, "message": "No next video", "cache_time": 0}))


def playlist_fixtures():
    w = lambda name, b: open(os.path.join(OUT, name), "wb").write(b)
    peer = ("inputPeerUser", {"user_id": 777, "access_hash": 5})
    w("req_save_file_part.bin", ser_ctor(BY_NAME["upload.saveFilePart"], {"file_id": 123456789, "file_part": 2, "bytes": b"hello part"}))
    media = ("inputMediaUploadedDocument", {
        "force_file": True,
        "file": ("inputFile", {"id": 123456789, "parts": 1, "name": "teleplay-backup-20260803-135000.json", "md5_checksum": ""}),
        "mime_type": "application/json",
        "attributes": [("documentAttributeFilename", {"file_name": "teleplay-backup-20260803-135000.json"})]})
    w("req_send_media.bin", ser_ctor(BY_NAME["messages.sendMedia"], {
        "peer": peer, "media": media, "message": "Playlist backup: 2 videos", "random_id": 42}))
    w("req_set_bot_commands.bin", ser_ctor(BY_NAME["bots.setBotCommands"], {
        "scope": ("botCommandScopeDefault", {}), "lang_code": "",
        "commands": [("botCommand", {"command": "export", "description": "Save my playlist as a backup file"}),
                     ("botCommand", {"command": "help", "description": "How to back up and restore"})]}))


def update_fixtures():
    w = lambda name, b: open(os.path.join(OUT, name), "wb").write(b)
    channel = ("channel", {"broadcast": True, "id": 3932023718, "access_hash": 2677590764230059536, "title": "TelePlay",
                           "username": "TelePlayUpdate", "photo": ("chatPhotoEmpty", {}), "date": 1700000000})
    w("req_resolve_username.bin", ser_ctor(BY_NAME["contacts.resolveUsername"], {"username": "TelePlayUpdate"}))
    w("resolved_peer_channel.bin", ser_ctor(BY_NAME["contacts.resolvedPeer"], {
        "peer": ("peerChannel", {"channel_id": 3932023718}), "chats": [channel], "users": []}))
    inp = ("inputChannel", {"channel_id": 3932023718, "access_hash": 2677590764230059536})
    w("req_channel_get_messages.bin", ser_ctor(BY_NAME["channels.getMessages"], {
        "channel": inp, "id": [("inputMessageID", {"id": 2})]}))

    def post(mid, text, media=None):
        f = {"id": mid, "peer_id": ("peerChannel", {"channel_id": 3932023718}), "date": 1700000000 + mid, "message": text}
        if media:
            f["media"] = media
        return ("message", f)

    def channel_messages(msgs):
        return ser_ctor(BY_NAME["messages.channelMessages"], {
            "pts": 1, "count": len(msgs), "messages": msgs, "topics": [], "chats": [channel], "users": []})

    apk = ("messageMediaDocument", {"document": ("document", {
        "id": 424242, "access_hash": 99, "file_reference": b"\x0a\x0b\x0c", "date": 1700000003,
        "mime_type": "application/octet-stream", "size": 2144427, "dc_id": 4,
        "attributes": [("documentAttributeFilename", {"file_name": "app-release.apk"})]})})
    w("channel_post_announcement.bin", channel_messages([post(2, "latest:\ncode: 2\nhttps://t.me/TelePlayUpdate/3")]))
    w("channel_post_release.bin", channel_messages([post(3, "", apk)]))
    w("channel_post_empty.bin", channel_messages([("messageEmpty", {"id": 9})]))


def main():
    os.makedirs(OUT, exist_ok=True)
    photo = ("messageMediaPhoto", {"photo": ("photo", {
        "id": 55, "access_hash": 66, "file_reference": b"\x09", "date": 1700000000,
        "sizes": [("photoSize", {"type": "x", "w": 800, "h": 600, "size": 5000})], "dc_id": 2})})
    service = ("messageService", {"id": 4, "peer_id": peer_user(777), "date": 1700000004, "action": ("messageActionPinMessage", {})})
    msgs = [message(1, 777, "send me this", doc_video()), message(2, 777, "hello"), message(3, 777, "", photo), service]
    open(os.path.join(OUT, "difference.bin"), "wb").write(difference(msgs))
    remote_fixtures()
    playlist_fixtures()
    update_fixtures()
    print("wrote", OUT)


main()
