/**
 * BootPatcher Telegram Bot Webhook
 * ================================
 * Fast, clean, interactive Android boot image patching.
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

  // 1. Handle Commands
  if (update.message && update.message.text) {
    const text = update.message.text.trim();
    const chatId = update.message.chat.id;

    if (text.startsWith("/start")) {
      const msg =
        "👋 *Welcome to BootPatcher!*\n\n" +
        "Patch your Android `boot.img` with custom kernels from *BruhKernel*.\n" +
        "Optimized for *Poco F6 (`peridot`)* & Universal Android 14 GKI.\n\n" +
        "*Available Kernel Flavours:*\n" +
        "• 🟣 *SukiSU Ultra* (KernelSU + Suki extensions)\n" +
        "• 🔵 *KernelSU-Next* (Modernized KernelSU fork)\n" +
        "• 🟤 *WKSU* (KernelSU with WildKernels tweaks)\n" +
        "• 🟢 *ReSukiSU* (Streamlined SukiSU build)\n\n" +
        "📎 *Send your stock `boot.img` as an uncompressed file to begin!*";
      await tgSend(token, "sendMessage", { chat_id: chatId, text: msg, parse_mode: "Markdown" });
      return;
    }

    if (text.startsWith("/help")) {
      const msg =
        "ℹ️ *BootPatcher Help*\n\n" +
        "1. Send your stock `boot.img` file directly to this chat.\n" +
        "2. Choose your preferred kernel flavour.\n" +
        "3. Wait ~2 minutes while your image is analyzed, patched, and repacked.\n" +
        "4. Flash the resulting `patched_boot.img` via Recovery or Fastboot.\n\n" +
        "• Kernel Source: `" + kRepo + "`\n" +
        "• Target Device: Poco F6 (`peridot`) / GKI 2.0";
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

    // Build keyboard with upload message_id: `p:${flavour}:${msgId}`
    const keyboard = BASE_FLAVOURS.map((f) => {
      const ver = versions[f.id] || "6.1.138";
      return [
        {
          text: `${f.label} (${ver})`,
          callback_data: `p:${f.id}:${msgId}`,
        },
      ];
    });

    const promptMsg =
      `📦 *Stock Boot Image Received*\n` +
      `━━━━━━━━━━━━━━━━━━━━\n` +
      `📄 *File:* \`${fname}\`\n` +
      `💾 *Size:* \`${sizeMb} MB\`\n` +
      `📱 *Target Device:* Poco F6 (\`peridot\`) / GKI 2.0\n` +
      `⚡ *Patching Engine:* \`magiskboot\`\n\n` +
      `👉 *Select your desired BruhKernel flavour:*`;

    await tgSend(token, "sendMessage", {
      chat_id: chatId,
      text: promptMsg,
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
      const uploadMsgId = parts[2]; // Message ID of the uploaded boot.img
      const chatId  = cq.message.chat.id;
      const statusMsgId = cq.message.message_id; // Message to update with live progress animation!

      await tgSend(token, "answerCallbackQuery", { callback_query_id: cq.id });

      const flavourTitle = BASE_FLAVOURS.find((f) => f.id === flavour)?.label || flavour;

      // Update the SAME message with loading animation
      await tgSend(token, "editMessageText", {
        chat_id: chatId,
        message_id: statusMsgId,
        text:
          `⚡ *Building Your Patched Boot Image*\n` +
          `━━━━━━━━━━━━━━━━━━━━\n` +
          `📦 *Flavour:* ${flavourTitle}\n` +
          `📱 *Target Device:* Poco F6 (\`peridot\`) / GKI 2.0\n` +
          `🔄 *Progress:* \`[■□□□□□□□□□] 10%\`\n` +
          `⏳ *Status:* Dispatching build runner...`,
        parse_mode: "Markdown",
      });

      // Dispatch GitHub Actions workflow passing status_message_id
      const dispatchOk = await dispatchGitHubAction(env, {
        chat_id:           String(chatId),
        message_id:        String(uploadMsgId),
        status_message_id: String(statusMsgId),
        kernel_variant:    flavour,
        kernel_repo:       kRepo,
        stock_kernel:      "Android GKI",
        device_target:     "peridot",
      });

      if (dispatchOk) {
        await tgSend(token, "editMessageText", {
          chat_id: chatId,
          message_id: statusMsgId,
          text:
            `⚡ *Building Your Patched Boot Image*\n` +
            `━━━━━━━━━━━━━━━━━━━━\n` +
            `📦 *Flavour:* ${flavourTitle}\n` +
            `📱 *Target Device:* Poco F6 (\`peridot\`) / GKI 2.0\n` +
            `🔄 *Progress:* \`[■■□□□□□□□□] 15%\`\n` +
            `⏳ *Status:* Cloud runner spinning up...`,
          parse_mode: "Markdown",
        });
      } else {
        await tgSend(token, "editMessageText", {
          chat_id: chatId,
          message_id: statusMsgId,
          text: "❌ *Failed to start build runner.* Please verify GitHub tokens and permissions.",
          parse_mode: "Markdown",
        });
      }
    }
  }
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
            if (m) {
              versions[key] = m[1];
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
