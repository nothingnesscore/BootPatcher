/**
 * BootPatcher Cloudflare Worker (Serverless Telegram Webhook)
 * ============================================================
 * Zero idle runners. Zero wasted GitHub Actions minutes.
 *
 * How it works:
 * 1. User sends boot.img to @umbromomento_bot.
 * 2. Worker inspects image headers & queries available kernel versions.
 * 3. User chooses their flavour button with respective target version.
 * 4. Worker dispatches GitHub Actions (patch-boot.yml) on-demand.
 * 5. Runner unpacks, verifies strings, replaces kernel, repacks,
 *    and delivers patched_boot.img directly to the user's Telegram!
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

  // 1. Handle Slash Commands
  if (update.message && update.message.text) {
    const text = update.message.text.trim();
    const chatId = update.message.chat.id;

    if (text.startsWith("/start")) {
      const msg =
        "👋 *Welcome to BootPatcher!*\n\n" +
        "I patch Android `boot.img` files on-demand using GKI kernels from *BruhKernel*.\n\n" +
        "⚡ *On-Demand Cloud Patching:*\n" +
        "• Inspects stock kernel uname & strings\n" +
        "• Let you pick from 4 flavours (SukiSU, KernelSU-Next, WKSU, ReSukiSU)\n" +
        "• Dedicated GitHub Actions runner executes patching sequentially\n" +
        "• Sends back your ready-to-flash `patched_boot.img` in ~3 mins!\n\n" +
        "📎 *Upload your stock boot.img as a File to get started!*";
      await tgSend(token, "sendMessage", { chat_id: chatId, text: msg, parse_mode: "Markdown" });
      return;
    }

    if (text.startsWith("/help")) {
      const msg =
        "ℹ️ *BootPatcher Help*\n\n" +
        "• Send a `boot.img` file as an uncompressed document.\n" +
        "• Available flavours: SukiSU, KernelSU-Next, WKSU, ReSukiSU.\n" +
        "• Kernels built by: `" + kRepo + "` (android14-6.1).\n" +
        "• Source code: https://github.com/" + repo;
      await tgSend(token, "sendMessage", { chat_id: chatId, text: msg, parse_mode: "Markdown" });
      return;
    }
  }

  // 2. Handle File Upload (boot.img)
  if (update.message && update.message.document) {
    const doc = update.message.document;
    const chatId = update.message.chat.id;
    const fname = doc.file_name || "boot.img";

    const lower = fname.toLowerCase();
    if (!lower.endsWith(".img") && !lower.includes("boot")) {
      await tgSend(token, "sendMessage", {
        chat_id: chatId,
        text: "⚠️ Please upload a valid `boot.img` file (ends in `.img` or contains `boot`).",
        parse_mode: "Markdown",
      });
      return;
    }

    const sizeMB = (doc.file_size / (1024 * 1024)).toFixed(2);

    // Query available flavour versions from GitHub API in parallel
    const versions = await fetchFlavourVersions(kRepo, env.GITHUB_TOKEN);

    // Create buttons showing flavour + respective version
    const keyboard = BASE_FLAVOURS.map((f) => {
      const ver = versions[f.id] || "6.1.138";
      return [
        {
          text: `${f.label} (${ver})`,
          callback_data: `p:${f.id}:${doc.file_id}:${encodeURIComponent(fname)}`,
        },
      ];
    });

    const msg =
      `🔍 *Boot Image Uploaded*\n` +
      `━━━━━━━━━━━━━━━━━━━━\n` +
      `📄 *File:* \`${fname}\`\n` +
      `💾 *Size:* \`${sizeMB} MB\`\n` +
      `🌐 *Kernel Source:* \`${kRepo}\`\n\n` +
      `Select which kernel flavour to patch with:`;

    await tgSend(token, "sendMessage", {
      chat_id: chatId,
      text: msg,
      parse_mode: "Markdown",
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
      const fileId  = parts[2];
      const fname   = decodeURIComponent(parts[3] || "boot.img");
      const chatId  = cq.message.chat.id;

      await tgSend(token, "answerCallbackQuery", { callback_query_id: cq.id });

      // Edit message to indicate runner dispatching
      await tgSend(token, "editMessageText", {
        chat_id: chatId,
        message_id: cq.message.message_id,
        text:
          `🚀 *GitHub Actions Runner Dispatched!*\n` +
          `━━━━━━━━━━━━━━━━━━━━\n` +
          `📄 *File:* \`${fname}\`\n` +
          `💉 *Flavour:* \`${flavour}\`\n` +
          `🌐 *Kernel Source:* \`${kRepo}\`\n\n` +
          `⏳ A dedicated runner is spinning up now:\n` +
          `1. Unpacking boot image\n` +
          `2. Parsing kernel strings & verifying banner\n` +
          `3. Replacing kernel with \`${flavour}\` build\n` +
          `4. Repacking with magiskboot\n\n` +
          `You will receive live runner inspection and your final \`patched_boot.img\` in 3–5 minutes! ☕`,
        parse_mode: "Markdown",
      });

      // Get direct file download link from Telegram API
      const fileRes = await tgSend(token, "getFile", { file_id: fileId });
      if (!fileRes.ok || !fileRes.result.file_path) {
        await tgSend(token, "sendMessage", {
          chat_id: chatId,
          text: "❌ Failed to retrieve file from Telegram. Please re-upload.",
        });
        return;
      }

      const fileUrl = `https://api.telegram.org/file/bot${token}/${fileRes.result.file_path}`;

      // Dispatch GitHub Actions workflow
      const dispatchOk = await dispatchGitHubAction(env, {
        boot_img_url:   fileUrl,
        chat_id:        String(chatId),
        kernel_variant: flavour,
        kernel_repo:    kRepo,
        stock_kernel:   "Android GKI",
      });

      if (!dispatchOk) {
        await tgSend(token, "sendMessage", {
          chat_id: chatId,
          text: "❌ Failed to dispatch GitHub Actions runner. Please verify repository tokens.",
        });
      }
    }
  }
}

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
