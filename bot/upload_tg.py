"""
Upload patched boot.img to Telegram via MTProto (bypasses 50MB HTTP Bot API limit)
Used inside GitHub Actions runner on-demand.
"""

import os
import sys
import asyncio
from telethon import TelegramClient

# Add project root and module dir to sys.path for robust importing
_current_dir = os.path.dirname(os.path.abspath(__file__))
_parent_dir = os.path.dirname(_current_dir)
sys.path.insert(0, _parent_dir)
sys.path.insert(0, _current_dir)

try:
    from bot.tg_progress import TelegramProgressReporter, delete_message, edit_message
except ImportError:
    from tg_progress import TelegramProgressReporter, delete_message, edit_message

API_ID = int(os.environ.get("TELEGRAM_API_ID", "2040"))
API_HASH = os.environ.get("TELEGRAM_API_HASH", "b18441a1ff607e10a989891a5462e627")
BOT_TOKEN = os.environ.get("TELEGRAM_BOT_TOKEN", "").strip()


async def upload_file(
    chat_id: int,
    file_path: str,
    caption: str,
    status_message_id: str = "",
    flavour: str = "SukiSU",
    device: str = "peridot",
):
    if not BOT_TOKEN:
        print("ERROR: TELEGRAM_BOT_TOKEN is missing!", file=sys.stderr, flush=True)
        sys.exit(1)

    if not os.path.exists(file_path):
        print(f"ERROR: File not found: {file_path}", file=sys.stderr, flush=True)
        sys.exit(1)

    size_mb = round(os.path.getsize(file_path) / (1024 * 1024), 2)
    print(f"Connecting to Telegram MTProto to upload {file_path} ({size_mb} MB) to chat {chat_id}...", flush=True)

    reporter = TelegramProgressReporter(
        chat_id=str(chat_id),
        status_message_id=str(status_message_id),
        flavour=flavour,
        device=device,
        min_interval=2.8,
    )

    client = TelegramClient("gh_runner_session", API_ID, API_HASH)
    await client.start(bot_token=BOT_TOKEN)

    try:
        last_reported_console = [-1]

        def progress(current, total):
            if total > 0:
                pct = int(current / total * 100)
                # Console output every 25% or 100%
                if pct >= last_reported_console[0] + 25 or pct == 100:
                    last_reported_console[0] = (pct // 25) * 25
                    cur_mb = round(current / (1024 * 1024), 1)
                    tot_mb = round(total / (1024 * 1024), 1)
                    print(f"Upload progress: {pct}% ({cur_mb}/{tot_mb} MB)", flush=True)

                # Dynamic live Telegram update (maps upload to 85% -> 99% overall workflow)
                reporter.update(
                    current,
                    total,
                    stage_label="⬆️ Uploading patched boot.img",
                    stage_min_pct=85,
                    stage_max_pct=99,
                )

        # Attempt upload with markdown formatting first; fallback to raw text if entity parsing fails
        try:
            await client.send_file(
                chat_id,
                file_path,
                caption=caption,
                parse_mode="md",
                progress_callback=progress,
            )
        except Exception as e:
            print(f"[Warning] Markdown send_file error: {e}. Retrying without parse_mode...", flush=True)
            await client.send_file(
                chat_id,
                file_path,
                caption=caption,
                parse_mode=None,
                progress_callback=progress,
            )

        print("Successfully delivered patched boot.img to Telegram chat!", flush=True)

        # Clean cache / delete temporary status message in Telegram
        if status_message_id:
            print(f"Cleaning up temporary progress status message {status_message_id}...", flush=True)
            deleted = delete_message(str(chat_id), str(status_message_id))
            if not deleted:
                # If deletion failed, update it to a clean final notification
                edit_message(
                    str(chat_id),
                    str(status_message_id),
                    "✅ *Patched Boot Image Delivered Successfully!*\nDelivered below ⬇️",
                )

    finally:
        await client.disconnect()


if __name__ == "__main__":
    if len(sys.argv) < 3:
        print("Usage: python upload_tg.py <chat_id> <file_path> [caption_file_or_text] [status_message_id] [flavour] [device]", flush=True)
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

    status_id = sys.argv[4] if len(sys.argv) > 4 else ""
    flv = sys.argv[5] if len(sys.argv) > 5 else "SukiSU"
    dev = sys.argv[6] if len(sys.argv) > 6 else "peridot"

    asyncio.run(upload_file(c_id, f_path, caption_text, status_id, flv, dev))

