/**
 * BootPatcher Cloudflare Worker (Serverless Telegram Webhook)
 * ============================================================
 * Zero idle runners. Zero wasted GitHub Actions minutes.
 * 100% Cloud-Powered: Works 24/7 even when user's PC is powered off.
 *
 * Capabilities:
 * 1. Handles Telegram /start and /help commands.
 * 2. Receives stock boot.img uploads of any size (up to 2GB via MTProto on runner).
 * 3. Queries latest BruhKernel builds and displays interactive flavour buttons.
 * 4. Dispatches GitHub Actions cloud runner on-demand with safe callback_data (<64 bytes).
 * 5. Runner downloads via MTProto, unpacks with magiskboot, inspects kernel strings,
 *    injects requested flavour, repacks, and sends patched_boot.img back to user!
 */

export default {
  async fetch(request, env) {
    if (request.method !== "POST") {
      return new Response("BootPatcher Webhook Active", { status: 200 });
    }

    try {
      const update = await request.json();
      await handleUpdate(update, env);
    } catch (err) {
      console.error("Error processing update:", err);
    }

    return new Response("OK", { status: 200 });
  },
};

const BASE_FLAVOURS = [
  { id: "SukiSU",        label: "🟣 SukiSU Ultra" },
  { id: "KernelSU-Next", label: "🔵 KernelSU-Next" },
  { id: "WKSU",          label: "🟤 WKSU" },
  { id: "ReSukiSU",      label: "🟢 ReSukiSU" },
];

async function handleUpdate(update, env) {
  const token = env.TELEGRAM_BOT_TOKEN;
  const repo  = env.GITHUB_REPO || "nothingnesscore/BootPatcher";
  const kRepo = env.KERNEL_REPO || "nothingnesscore/BruhKernel";

  if (!token) {
    console.error("TELEGRAM_BOT_TOKEN missing");
    return;
  }

  // 1. Handle Slash Commands
  if (update.message && update.message.text) {
    const text = update.message.text.trim();
    const chatId = update.message.chat.id;

    if (text.startsWith("/start")) {
      const msg =
        "👋 *Welcome to BootPatcher Cloud Bot!*\n\n" +
        "I patch Android `boot.img` files on-demand using GKI kernels from *BruhKernel* (Poco F6 / `peridot` & Universal GKI).\n\n" +
        "⚡ *How It Works:*\n" +
        "1️⃣ Send your stock `boot.img` as an uncompressed *File* (any size, up to 2GB!)\n" +
        "2️⃣ Choose your flavour: *🟣 SukiSU Ultra, 🔵 KernelSU-Next, 🟤 WKSU, or 🟢 ReSukiSU*\n" +
        "3️⃣ A dedicated GitHub Actions cloud runner fires up on-demand to unpack, inspect kernel strings with `magiskboot`, and inject your chosen flavour\n" +
        "4️⃣ Receive your ready-to-flash `patched_boot.img` directly here in ~2–3 mins! 🚀\n\n" +
        "💡 *100% Cloud-Powered:* You can turn off your PC at any time — everything runs serverless in the cloud 24/7!\n\n" +
        "📎 *Upload your stock boot.img as a File to begin!*";
      await tgSend(token, "sendMessage", { chat_id: chatId, text: msg, parse_mode: "Markdown" });
      return;
    }

    if (text.startsWith("/help")) {
      const msg =
        "ℹ️ *BootPatcher Cloud Help*\n\n" +
        "• Send your phone's stock `boot.img` file.\n" +
        "• Kernels sourced from: `" + kRepo + "` (android14-6.1)\n" +
        "• Device target: Poco F6 (`peridot`) & Universal GKI\n" +
        "• Patching engine: `magiskboot` on clean Linux runners\n" +
        "• Repository: https://github.com/" + repo + "\n\n" +
        "Everything runs in the cloud on-demand. No local PC required!";
      await tgSend(token, "sendMessage", { chat_id: chatId, text: msg, parse_mode: "Markdown" });
      return;
    }
  }

  // 2. Handle File Upload (boot.img)
  if (update.message && update.message.document) {
    const doc = update.message.document;
    const chatId = update.message.chat.id;
    const msgId = update.message.message_id;
    const fname = doc.file_name || "boot.img";
    const sizeMb = (doc.file_size / (1024 * 1024)).toFixed(2);

    const lower = fname.toLowerCase();
    if (!lower.endsWith(".img") && !lower.includes("boot")) {
      await tgSend(token, "sendMessage", {
        chat_id: chatId,
        text: "⚠️ Please upload a valid `boot.img` file (ends in `.img` or contains `boot`).",
        parse_mode: "Markdown",
        reply_parameters: { message_id: msgId },
      });
      return;
    }

    // Query available flavour versions from GitHub API in parallel
    const versions = await fetchFlavourVersions(kRepo, env.GITHUB_TOKEN);

    // Build inline keyboard with message_id in callback_data: `p:${flavour}:${msgId}` (always < 30 bytes)
    const keyboard = BASE_FLAVOURS.map((f) => {
      const ver = versions[f.id] || "6.1.138";
      return [
        {
          text: `${f.label} (${ver})`,
          callback_data: `p:${f.id}:${msgId}`,
        },
      ];
    });

    let headerInfo = "";
    // If file is <= 20 MB, we can inspect headers via Telegram Bot API
    if (doc.file_size <= 20 * 1024 * 1024) {
      try {
        const fileRes = await tgSend(token, "getFile", { file_id: doc.file_id });
        if (fileRes.ok && fileRes.result.file_path) {
          const fileUrl = `https://api.telegram.org/file/bot${token}/${fileRes.result.file_path}`;
          const rangeRes = await fetch(fileUrl, { headers: { Range: "bytes=0-262143" } });
          if (rangeRes.ok || rangeRes.status === 206) {
            const buffer = await rangeRes.arrayBuffer();
            const parsed = parseBootBuffer(buffer, doc.file_size);
            if (parsed.kernel_short !== "Unknown") {
              headerInfo = `\n🐧 *Detected Stock Kernel:* \`${parsed.kernel_short}\``;
            }
          }
        }
      } catch (e) {
        console.error("Header inspection error:", e);
      }
    }

    const analysisMsg =
      `📦 *Stock boot.img Received!*\\n` +
      `━━━━━━━━━━━━━━━━━━━━\n` +
      `📄 *File:* \`${fname}\`\n` +
      `💾 *Size:* \`${sizeMb} MB\`\n` +
      `📱 *Target:* \`peridot\` / Android 14 GKI\n` +
      `⚡ *Engine:* \`magiskboot\` Cloud Runner${headerInfo}\n\n` +
      `👉 *Select your desired BruhKernel flavour below:*\n` +
      `_(Dedicated runner will spin up on-demand to inspect kernel banner & strings, inject your chosen flavour, repack, and send it back to you!)_`;

    await tgSend(token, "sendMessage", {
      chat_id: chatId,
      text: analysisMsg,
      parse_mode: "Markdown",
      reply_parameters: { message_id: msgId },
      reply_markup: { inline_keyboard: keyboard },
    });
    return;
  }

  // 3. Handle Flavour Button Selection (Callback Query)
  if (update.callback_query) {
    const cq = update.callback_query;
    const data = cq.data || "";

    if (data.startsWith("p:")) {
      const parts = data.split(":");
      const flavour = parts[1];
      const uploadMsgId = parts[2]; // Message ID of the uploaded boot.img document
      const chatId  = cq.message.chat.id;
      const msgId   = cq.message.message_id;

      await tgSend(token, "answerCallbackQuery", { callback_query_id: cq.id });

      const flavourTitle = BASE_FLAVOURS.find((f) => f.id === flavour)?.label || flavour;

      // Update message to show live cloud runner status
      await tgSend(token, "editMessageText", {
        chat_id: chatId,
        message_id: msgId,
        text:
          `🚀 *Cloud Runner Dispatched!*\n` +
          `━━━━━━━━━━━━━━━━━━━━\n` +
          `💉 *Flavour:* ${flavourTitle}\n` +
          `🌐 *Kernel Source:* \`${kRepo}\`\n` +
          `📱 *Device Target:* \`peridot\` / Android 14 GKI\n\n` +
          `⏳ *Live Cloud Steps (Running Now):*\n` +
          `1️⃣ Downloading \`boot.img\` via MTProto (no 20MB limit)\n` +
          `2️⃣ Unpacking with \`magiskboot\` & verifying kernel strings\n` +
          `3️⃣ Injecting \`${flavour}\` AnyKernel3 build\n` +
          `4️⃣ Repacking & uploading \`patched_boot.img\` directly to this chat\n\n` +
          `☕ *Your PC can stay OFF!* Everything is processing 100% in the cloud. You will receive your ready-to-flash file here in ~2–3 minutes!`,
        parse_mode: "Markdown",
      });

      // Dispatch GitHub Actions workflow
      const dispatchOk = await dispatchGitHubAction(env, {
        chat_id:        String(chatId),
        message_id:     String(uploadMsgId),
        kernel_variant: flavour,
        kernel_repo:    kRepo,
        stock_kernel:   "Android GKI",
        device_target:  "peridot",
      });

      if (!dispatchOk) {
        await tgSend(token, "sendMessage", {
          chat_id: chatId,
          text: "❌ Failed to dispatch GitHub Actions runner. Please verify repository tokens and permissions.",
        });
      }
    }
  }
}

// ── Binary Parser Helper ────────────────────────────────────────────────────

function parseBootBuffer(arrayBuffer, totalSize) {
  const info = {
    format: "Android Boot Image",
    kernel_short: "Unknown",
  };
  const bytes = new Uint8Array(arrayBuffer);

  const textDecoder = new TextDecoder("utf-8", { fatal: false, ignoreBOM: true });
  const str = textDecoder.decode(bytes);

  const bannerMatch = str.match(/Linux version ([^\x00\r\n]+)/);
  if (bannerMatch) {
    const verNum = bannerMatch[1].match(/(\d+\.\d+\.\d+[\w.-]*)/);
    if (verNum) {
      info.kernel_short = verNum[1];
    }
  }

  return info;
}

// ── GitHub & Telegram API Helpers ───────────────────────────────────────────

async function fetchFlavourVersions(kRepo, ghToken) {
  const versions = { SukiSU: "6.1.138", "KernelSU-Next": "6.1.138", WKSU: "6.1.138", ReSukiSU: "6.1.138" };
  const headers = {
    "Accept": "application/vnd.github+json",
    "User-Agent": "BootPatcher-Cloudflare-Worker",
  };
  if (ghToken) {
    headers["Authorization"] = `Bearer ${ghToken}`;
  }

  try {
    const res = await fetch(`https://api.github.com/repos/${kRepo}/actions/artifacts?per_page=50`, { headers });
    if (res.ok) {
      const data = await res.json();
      for (const a of (data.artifacts || [])) {
        if (a.expired) continue;
        const name = a.name || "";
        for (const key of Object.keys(versions)) {
          if (name.toLowerCase().includes(key.toLowerCase()) && name.toLowerCase().includes("anykernel3")) {
            const m = name.match(/(\d+\.\d+\.\d+)/);
            let dev = "";
            if (name.toLowerCase().includes("peridot")) {
              dev = "peridot ";
            }
            if (m) {
              versions[key] = dev ? `${dev}${m[1]}` : m[1];
            }
          }
        }
      }
    }
  } catch (e) {
    console.error("Failed querying flavour versions:", e);
  }

  return versions;
}

async function tgSend(token, method, payload) {
  const url = `https://api.telegram.org/bot${token}/${method}`;
  const res = await fetch(url, {
    method: "POST",
    headers: { "Content-Type": "application/json" },
    body: JSON.stringify(payload),
  });
  return res.json();
}

async function dispatchGitHubAction(env, inputs) {
  const repo = env.GITHUB_REPO || "nothingnesscore/BootPatcher";
  const url = `https://api.github.com/repos/${repo}/actions/workflows/patch-boot.yml/dispatches`;

  const res = await fetch(url, {
    method: "POST",
    headers: {
      "Authorization": `Bearer ${env.GITHUB_TOKEN}`,
      "Accept": "application/vnd.github+json",
      "User-Agent": "BootPatcher-Serverless-Webhook",
      "X-GitHub-Api-Version": "2022-11-28",
      "Content-Type": "application/json",
    },
    body: JSON.stringify({
      ref: "main",
      inputs: inputs,
    }),
  });

  return res.status === 204;
}
