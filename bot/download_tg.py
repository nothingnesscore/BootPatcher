"""
Download boot.img from Telegram via MTProto (no 20MB Bot API limit)
Used inside GitHub Actions runner on-demand.
"""

import os
import sys
import asyncio
from telethon import TelegramClient

API_ID = int(os.environ.get("TELEGRAM_API_ID", "2040"))
API_HASH = os.environ.get("TELEGRAM_API_HASH", "b18441a1ff607e10a989891a5462e627")
BOT_TOKEN = os.environ.get("TELEGRAM_BOT_TOKEN", "").strip()

async def download_file(chat_id: int, message_id: int, output_path: str):
    if not BOT_TOKEN:
        print("ERROR: TELEGRAM_BOT_TOKEN is missing!", file=sys.stderr, flush=True)
        sys.exit(1)

    print(f"Connecting to Telegram MTProto as bot for chat={chat_id}, msg={message_id}...", flush=True)
    client = TelegramClient("gh_runner_session", API_ID, API_HASH)
    await client.start(bot_token=BOT_TOKEN)

    try:
        msg = await client.get_messages(chat_id, ids=message_id)
        if not msg or not msg.document:
            print(f"ERROR: No document found in chat {chat_id} at message {message_id}", file=sys.stderr, flush=True)
            sys.exit(1)

        size_mb = round(msg.document.size / (1024 * 1024), 2)
        fname = msg.file.name or "boot.img"
        print(f"Found document: {fname} ({size_mb} MB). Downloading via MTProto...", flush=True)

        last_reported = [0]
        def progress(current, total):
            if total > 0:
                pct = int(current / total * 100)
                if pct >= last_reported[0] + 25:
                    last_reported[0] = (pct // 25) * 25
                    cur_mb = round(current / (1024 * 1024), 1)
                    tot_mb = round(total / (1024 * 1024), 1)
                    print(f"Download progress: {last_reported[0]}% ({cur_mb}/{tot_mb} MB)", flush=True)

        await client.download_media(msg, file=output_path, progress_callback=progress)
        print(f"Successfully downloaded to {output_path} ({os.path.getsize(output_path)} bytes)", flush=True)
    finally:
        await client.disconnect()

if __name__ == "__main__":
    if len(sys.argv) < 4:
        print("Usage: python download_tg.py <chat_id> <message_id> <output_path>", flush=True)
        sys.exit(1)

    c_id = int(sys.argv[1])
    m_id = int(sys.argv[2])
    out_file = sys.argv[3]

    asyncio.run(download_file(c_id, m_id, out_file))
