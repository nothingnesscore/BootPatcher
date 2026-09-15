"""
Upload patched boot.img to Telegram via MTProto (bypasses 50MB HTTP Bot API limit)
Used inside GitHub Actions runner on-demand.
"""

import os
import sys
import asyncio
from telethon import TelegramClient

API_ID = int(os.environ.get("TELEGRAM_API_ID", "2040"))
API_HASH = os.environ.get("TELEGRAM_API_HASH", "b18441a1ff607e10a989891a5462e627")
BOT_TOKEN = os.environ.get("TELEGRAM_BOT_TOKEN", "").strip()

async def upload_file(chat_id: int, file_path: str, caption: str):
    if not BOT_TOKEN:
        print("ERROR: TELEGRAM_BOT_TOKEN is missing!", file=sys.stderr, flush=True)
        sys.exit(1)

    if not os.path.exists(file_path):
        print(f"ERROR: File not found: {file_path}", file=sys.stderr, flush=True)
        sys.exit(1)

    size_mb = round(os.path.getsize(file_path) / (1024 * 1024), 2)
    print(f"Connecting to Telegram MTProto to upload {file_path} ({size_mb} MB) to chat {chat_id}...", flush=True)

    client = TelegramClient("gh_runner_session", API_ID, API_HASH)
    await client.start(bot_token=BOT_TOKEN)

    try:
        last_reported = [0]
        def progress(current, total):
            if total > 0:
                pct = int(current / total * 100)
                if pct >= last_reported[0] + 25:
                    last_reported[0] = (pct // 25) * 25
                    cur_mb = round(current / (1024 * 1024), 1)
                    tot_mb = round(total / (1024 * 1024), 1)
                    print(f"Upload progress: {last_reported[0]}% ({cur_mb}/{tot_mb} MB)", flush=True)

        await client.send_file(
            chat_id,
            file_path,
            caption=caption,
            parse_mode="md",
            progress_callback=progress
        )
        print("Successfully sent patched boot.img to Telegram chat!", flush=True)
    finally:
        await client.disconnect()

if __name__ == "__main__":
    if len(sys.argv) < 3:
        print("Usage: python upload_tg.py <chat_id> <file_path> [caption_file_or_text]", flush=True)
        sys.exit(1)

    c_id = int(sys.argv[1])
    f_path = sys.argv[2]
    
    caption_text = ""
    if len(sys.argv) > 3:
        arg3 = sys.argv[3]
        if os.path.exists(arg3):
            with open(arg3, "r", encoding="utf-8") as f:
                caption_text = f.read()
        else:
            caption_text = arg3

    asyncio.run(upload_file(c_id, f_path, caption_text))
