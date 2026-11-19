// Cloudflare Worker: активация лицензий + 4 Telegram-бота для Java Decompiler.
//
// Маршруты:
//   GET  /                  — health-check
//   GET  /activate?hwid=&key= — активация (вызывает C++-лоадер)
//   GET  /report?hwid=&event=&detail= — событие безопасности (шлёт бот 4)
//   POST /tg/admin          — бот 2: команды админа (алиасы: /tg/key, /tg/users)
//   POST /tg/shop           — бот 3: покупка ключей (алиас: /tg/buy)
//
// Боты:
//   1) HWID-уведомления  — HWIDBOT_TOKEN / HWIDBOT_ADMIN (только исходящие)
//   2) админ-команды     — KEYBOT_TOKEN + ADMIN_CHAT_ID
//   3) покупка           — SHOPBOT_TOKEN (+ PRICE_1M/3M/1Y в звёздах)
//   4) безопасность      — SECURITYBOT_TOKEN / SECURITY_ADMIN
//
// Привязка DB — D1, см. wrangler.toml / DEPLOY.md.

const ALPHABET = 'ABCDEFGHJKLMNPQRSTUVWXYZ23456789';
const DURATIONS = { '1m': 30, '3m': 90, '1y': 365 };
const DUR_LABEL = { '1m': '1 месяц', '3m': '3 месяца', '1y': '1 год' };

const now = () => Math.floor(Date.now() / 1000);

const json = (obj, status = 200) =>
  new Response(JSON.stringify(obj), {
    status,
    headers: { 'content-type': 'application/json; charset=utf-8', 'cache-control': 'no-store' },
  });

const ok = () => new Response('ok');

const esc = (s) => String(s ?? '').replace(/&/g, '&amp;').replace(/</g, '&lt;').replace(/>/g, '&gt;');

function fmt(ts, env, withTime = false) {
  const tz = env.TZ || 'Europe/Moscow';
  const opts = { timeZone: tz, day: '2-digit', month: '2-digit', year: 'numeric' };
  if (withTime) { opts.hour = '2-digit'; opts.minute = '2-digit'; }
  try {
    return new Intl.DateTimeFormat('ru-RU', opts).format(new Date(ts * 1000));
  } catch {
    return new Date(ts * 1000).toISOString().slice(0, 10);
  }
}

function genKey() {
  const b = new Uint8Array(16);
  crypto.getRandomValues(b);
  const g = [];
  for (let i = 0; i < 4; i++) {
    let s = '';
    for (let j = 0; j < 4; j++) s += ALPHABET[b[i * 4 + j] % ALPHABET.length];
    g.push(s);
  }
  return g.join('-');
}

async function tgApi(token, method, payload) {
  if (!token) return null;
  try {
    const r = await fetch(`https://api.telegram.org/bot${token}/${method}`, {
      method: 'POST',
      headers: { 'content-type': 'application/json' },
      body: JSON.stringify(payload),
    });
    return await r.json().catch(() => null);
  } catch {
    return null;
  }
}

const tgSend = (token, chatId, text) =>
  tgApi(token, 'sendMessage', { chat_id: chatId, text, parse_mode: 'HTML', disable_web_page_preview: true });

// ---------------- уведомления ----------------

async function notifyActivation(env, hwid, key, owner, notAfter) {
  const text =
    '<b>Новая/обновлённая активация</b>\n' +
    `HWID: <code>${esc(hwid)}</code>\n` +
    `Ключ: <code>${esc(key)}</code>\n` +
    `Владелец: ${esc(owner || 'не указан')}\n` +
    `Действует до: ${fmt(notAfter, env, true)}`;
  return tgSend(env.HWIDBOT_TOKEN, env.HWIDBOT_ADMIN || env.ADMIN_CHAT_ID, text);
}

async function notifySecurity(env, text) {
  return tgSend(env.SECURITYBOT_TOKEN || env.HWIDBOT_TOKEN,
                env.SECURITY_ADMIN || env.HWIDBOT_ADMIN || env.ADMIN_CHAT_ID, text);
}

// ---------------- /activate ----------------

async function activate(url, env, ctx) {
  const hwid = (url.searchParams.get('hwid') || '').trim();
  const key = (url.searchParams.get('key') || '').trim().toUpperCase();
  if (!hwid || !key) return json({ status: 'invalid_key' });

  const row = await env.DB.prepare('SELECT * FROM keys WHERE key=?').bind(key).first();
  if (!row) return json({ status: 'invalid_key' });
  if (row.status === 'revoked') return json({ status: 'invalid_key' });
  if (row.expires < now()) return json({ status: 'expired' });

  if (!row.hwid) {
    await env.DB.prepare("UPDATE keys SET hwid=?, status='active' WHERE key=?").bind(hwid, key).run();
    ctx.waitUntil(notifyActivation(env, hwid, key, row.owner, row.expires));
    return json({ status: 'ok', not_before: row.created, not_after: row.expires });
  }

  if (row.hwid !== hwid) {
    ctx.waitUntil(notifySecurity(env,
      `<b>⚠️ Чужой HWID</b>\nКлюч: <code>${esc(key)}</code>\n` +
      `Привязан: <code>${esc((row.hwid || '').slice(0, 16))}…</code>\n` +
      `Пришёл: <code>${esc(hwid.slice(0, 16))}…</code>`));
    return json({ status: 'wrong_hwid' });
  }

  return json({ status: 'ok', not_before: row.created, not_after: row.expires });
}

// ---------------- /report (бот 4) ----------------

async function report(url, env, ctx) {
  const hwid = (url.searchParams.get('hwid') || '').slice(0, 64);
  const event = (url.searchParams.get('event') || 'unknown').slice(0, 64);
  const detail = (url.searchParams.get('detail') || '').slice(0, 300);
  if (!hwid) return json({ ok: false }, 400);

  const row = await env.DB.prepare('SELECT key,owner,status FROM keys WHERE hwid=? LIMIT 1')
    .bind(hwid).first().catch(() => null);

  const text =
    '<b>🚨 Событие безопасности</b>\n' +
    `Событие: <code>${esc(event)}</code>\n` +
    `HWID: <code>${esc(hwid)}</code>\n` +
    (row ? `Ключ: <code>${esc(row.key)}</code>\nВладелец: ${esc(row.owner || '—')}\n` : '') +
    (detail ? `Детали: ${esc(detail)}\n` : '') +
    `Время: ${fmt(now(), env, true)}`;

  ctx.waitUntil(notifySecurity(env, text));
  return json({ ok: true });
}

// ---------------- Telegram: общее ----------------

async function readUpdate(req, env, secretVar) {
  if (req.method !== 'POST') return null;
  const secret = secretVar ? env[secretVar] : null;
  if (secret && req.headers.get('x-telegram-bot-api-secret-token') !== secret) return null;
  try { return await req.json(); } catch { return null; }
}

const isAdmin = (msg, env) => String(msg?.chat?.id) === String(env.ADMIN_CHAT_ID);

function parse(text) {
  const parts = text.trim().split(/\s+/);
  return { cmd: parts[0].split('@')[0], args: parts.slice(1) };
}

// ---------------- Бот 2: админ ----------------

async function runAdmin(cmd, args, env, send) {
  const db = env.DB;
  const t = now();

  const fmtExp = (e) => (e < t ? `<s>${fmt(e, env)}</s> (истёк)` : fmt(e, env));

  switch (cmd) {
    case '/gen': {
      if (!args.length) return send('Использование: /gen 1m | 3m | 1y');
      const dur = args[0].trim().toLowerCase();
      if (!DURATIONS[dur]) return send('Допустимо: 1m, 3m, 1y');
      const k = genKey();
      const created = t;
      const expires = created + DURATIONS[dur] * 86400;
      await db.prepare('INSERT INTO keys(key,duration,created,expires,status) VALUES(?,?,?,?,?)')
        .bind(k, dur, created, expires, 'unused').run();
      return send(`Ключ сгенерирован:\n<code>${esc(k)}</code>\nСрок: ${dur} (до ${fmt(expires, env, true)})`);
    }
    case '/keys': {
      const { results } = await db.prepare(
        'SELECT key,duration,status FROM keys ORDER BY created DESC LIMIT 30').all();
      if (!results.length) return send('Ключей пока нет.');
      return send(results.map(r => `${esc(r.key)}  [${esc(r.duration)}]  ${esc(r.status)}`).join('\n'));
    }
    case '/revoke': {
      const k = (args[0] || '').trim().toUpperCase();
      const res = await db.prepare("UPDATE keys SET status='revoked' WHERE key=?").bind(k).run();
      return send(res.meta.changes ? 'Отозвано.' : 'Ключ не найден.');
    }
    case '/hwid': {
      const k = (args[0] || '').trim().toUpperCase();
      const r = await db.prepare('SELECT hwid,status,expires,owner FROM keys WHERE key=?').bind(k).first();
      if (!r) return send('Ключ не найден.');
      return send(`owner=${esc(r.owner || '—')}\nhwid=${esc(r.hwid || '—')}\nstatus=${esc(r.status)}\nдо=${esc(fmt(r.expires, env, true))}`);
    }
    case '/grant': {
      if (args.length < 2) return send('Использование: /grant &lt;ключ&gt; &lt;ник&gt;');
      const k = args[0].trim().toUpperCase();
      const owner = args.slice(1).join(' ');
      const res = await db.prepare('UPDATE keys SET owner=? WHERE key=?').bind(owner, k).run();
      return send(res.meta.changes ? 'Владелец записан.' : 'Ключ не найден.');
    }
    case '/total': {
      const { results } = await db.prepare('SELECT status, COUNT(*) AS c FROM keys GROUP BY status').all();
      if (!results.length) return send('Ключей нет.');
      return send(results.map(r => `${esc(r.status)}: ${r.c}`).join('\n'));
    }
    case '/users': {
      const { results } = await db.prepare(
        "SELECT key,hwid,status,expires,owner FROM keys WHERE status='active' ORDER BY expires").all();
      if (!results.length) return send('Активных пользователей нет.');
      let buf = '';
      for (const r of results) {
        const ln = `<b>${esc(r.owner || '—')}</b>\n  hwid: ${r.hwid ? esc(r.hwid.slice(0, 16)) + '…' : '—'}\n` +
          `  ключ: ${esc(r.key)}\n  до: ${fmtExp(r.expires)}\n\n`;
        if (buf.length + ln.length > 3800) { await send(buf); buf = ''; }
        buf += ln;
      }
      if (buf.trim()) await send(buf);
      return;
    }
    case '/find': {
      const q = args[0] || '';
      const { results } = await db.prepare(
        'SELECT key,hwid,status,expires,owner FROM keys WHERE hwid LIKE ?').bind(`%${q}%`).all();
      if (!results.length) return send('Ничего не найдено.');
      return send(results.map(r =>
        `<b>${esc(r.owner || '—')}</b> [${esc(r.status)}] до ${fmtExp(r.expires)}\n  hwid: ${esc(r.hwid || '—')}`
      ).join('\n\n'));
    }
    case '/expire': {
      const days = parseInt(args[0], 10);
      if (!Number.isFinite(days)) return send('Использование: /expire &lt;дней&gt;');
      const { results } = await db.prepare(
        "SELECT key,expires,owner FROM keys WHERE status='active' AND expires<=? ORDER BY expires")
        .bind(t + days * 86400).all();
      if (!results.length) return send(`Нет ключей, истекающих в ближайшие ${days} дн.`);
      return send(results.map(r => `<b>${esc(r.owner || '—')}</b> до ${fmtExp(r.expires)}\n  ${esc(r.key)}`).join('\n\n'));
    }
    case '/status': {
      const k = (args[0] || '').trim().toUpperCase();
      const r = await db.prepare('SELECT * FROM keys WHERE key=?').bind(k).first();
      if (!r) return send('Ключ не найден.');
      return send(`Ключ: <b>${esc(r.key)}</b>\nвладелец: ${esc(r.owner || '—')}\nhwid: ${esc(r.hwid || '—')}\n` +
        `статус: ${esc(r.status)}\nдо: ${fmtExp(r.expires)}\nвыдан: ${fmt(r.created, env)}`);
    }
    case '/start':
    case '/help':
      return send(
        '<b>Админ-команды</b>\n' +
        '/gen 1m|3m|1y — новый ключ\n' +
        '/keys — последние 30\n' +
        '/revoke &lt;ключ&gt; — отозвать\n' +
        '/hwid &lt;ключ&gt; — привязка\n' +
        '/grant &lt;ключ&gt; &lt;ник&gt; — владелец\n' +
        '/total — сводка по статусам\n' +
        '/users — активные юзеры\n' +
        '/find &lt;hwid&gt; — поиск\n' +
        '/expire &lt;дней&gt; — скоро истекут\n' +
        '/status &lt;ключ&gt; — детали');
    default:
      return send('Неизвестная команда. /help');
  }
}

async function tgAdmin(req, env) {
  const update = await readUpdate(req, env, 'KEYBOT_WEBHOOK_SECRET');
  if (!update) return ok();
  const msg = update.message || update.edited_message;
  if (!msg || !msg.text) return ok();
  if (!isAdmin(msg, env)) return ok();

  const { cmd, args } = parse(msg.text);
  const send = (t) => tgSend(env.KEYBOT_TOKEN, msg.chat.id, t);
  try {
    await runAdmin(cmd, args, env, send);
  } catch (e) {
    await send('Ошибка: ' + esc(e && e.message || e));
  }
  return ok();
}

// ---------------- Бот 3: покупка ----------------

function prices(env) {
  return {
    '1m': parseInt(env.PRICE_1M || '150', 10),
    '3m': parseInt(env.PRICE_3M || '400', 10),
    '1y': parseInt(env.PRICE_1Y || '1200', 10),
  };
}

const resetPrice = (env) => parseInt(env.PRICE_RESET || '259', 10);

// Владелец: owner пустой — ключ ещё ни за кем не закреплён (первый владелец),
// иначе owner должен совпадать с telegram id.
const ownsKey = (row, chatId) => {
  const owner = String(row.owner || '').trim();
  if (!owner) return true;
  return owner === String(chatId);
};

async function shopMenu(env, chatId) {
  const p = prices(env);
  const text =
    '<b>Unflatten Decompiler</b>\n\n' +
    'Ключ привязывается к вашему компьютеру (HWID) и работает без интернета.\n\n' +
    `• 1 месяц — <b>${p['1m']}</b> ⭐\n` +
    `• 3 месяца — <b>${p['3m']}</b> ⭐\n` +
    `• 1 год — <b>${p['1y']}</b> ⭐\n\n` +
    'Выберите срок:';
  const keyboard = {
    inline_keyboard: [
      [{ text: `1 месяц — ${p['1m']} ⭐`, callback_data: 'buy:1m' }],
      [{ text: `3 месяца — ${p['3m']} ⭐`, callback_data: 'buy:3m' }],
      [{ text: `1 год — ${p['1y']} ⭐`, callback_data: 'buy:1y' }],
      [{ text: `🔄 Сброс HWID — ${resetPrice(env)} ⭐`, callback_data: 'reset' }],
      [{ text: '💳 Купить на FunPay', url: env.FUNPAY_URL || 'https://funpay.com/users/3407632/' }],
    ],
  };
  return tgApi(env.SHOPBOT_TOKEN, 'sendMessage', {
    chat_id: chatId, text, parse_mode: 'HTML', reply_markup: keyboard,
  });
}

async function shopInvoice(env, chatId, dur) {
  const p = prices(env);
  const amount = p[dur];
  if (!amount) return;
  return tgApi(env.SHOPBOT_TOKEN, 'sendInvoice', {
    chat_id: chatId,
    title: `Java Decompiler — ${DUR_LABEL[dur]}`,
    description: `Лицензионный ключ на ${DUR_LABEL[dur]}. Привязка к вашему ПК (HWID).`,
    payload: `buy:${dur}:${Date.now()}`,
    currency: 'XTR',
    prices: [{ label: DUR_LABEL[dur], amount }],
  });
}

async function shopResetInvoice(env, chatId, key) {
  return tgApi(env.SHOPBOT_TOKEN, 'sendInvoice', {
    chat_id: chatId,
    title: 'Сброс HWID',
    description: `Отвязка ключа ${key} от текущего компьютера. Срок подписки не меняется.`,
    payload: `reset:${key}`,
    currency: 'XTR',
    prices: [{ label: 'Сброс HWID', amount: resetPrice(env) }],
  });
}

async function shopPaid(env, msg) {
  const sp = msg.successful_payment;
  const payload = sp.invoice_payload || '';

  if (payload.startsWith('reset:')) {
    const key = payload.slice(6).trim().toUpperCase();
    const row = await env.DB.prepare('SELECT key,hwid,owner FROM keys WHERE key=?').bind(key).first();
    if (!row) {
      await tgSend(env.SHOPBOT_TOKEN, msg.chat.id,
        'Оплата получена, но ключ не найден. Напишите в поддержку с этим сообщением.');
      await notifySecurity(env,
        `<b>⚠️ Оплата сброса без ключа</b>\npayload: <code>${esc(payload)}</code>\n` +
        `user: ${esc(msg.from?.id || '—')}`);
      return;
    }
    if (!ownsKey(row, msg.chat.id)) {
      await tgSend(env.SHOPBOT_TOKEN, msg.chat.id,
        'Оплата получена, но ключ закреплён за другим аккаунтом — сброс не выполнен. ' +
        'Напишите в поддержку, вернём звёзды.');
      await notifySecurity(env,
        `<b>⚠️ Оплата сброса чужого ключа</b>\nКлюч: <code>${esc(key)}</code>\n` +
        `owner: <code>${esc(row.owner || '—')}</code>\n` +
        `плательщик: ${esc(msg.from?.id || '—')} (@${esc(msg.from?.username || '—')})`);
      return;
    }

    await env.DB.prepare(
      "UPDATE keys SET hwid=NULL, status='unused', owner=COALESCE(NULLIF(owner,''), ?) WHERE key=?")
      .bind(String(msg.from?.id || ''), key).run();
    await env.DB.prepare(
      'INSERT INTO sales(key,duration,stars,tg_user,tg_username,ts,charge_id) VALUES(?,?,?,?,?,?,?)')
      .bind(key, 'reset', sp.total_amount || 0, msg.from?.id || 0, msg.from?.username || '',
            now(), sp.telegram_payment_charge_id || '').run().catch(() => {});

    await tgSend(env.SHOPBOT_TOKEN, msg.chat.id,
      '<b>HWID сброшен</b>\n\n' +
      `Ключ: <code>${esc(key)}</code>\n` +
      'Активируйте его на нужном компьютере — срок подписки не изменился.');
    await tgSend(env.KEYBOT_TOKEN || env.HWIDBOT_TOKEN, env.ADMIN_CHAT_ID,
      '<b>🔄 Покупка сброса HWID</b>\n' +
      `Ключ: <code>${esc(key)}</code>\nСумма: ${sp.total_amount} ⭐\n` +
      `Покупатель: @${esc(msg.from?.username || msg.from?.id || '—')}`);
    await notifySecurity(env,
      `<b>🔄 Сброс HWID</b>\nКлюч: <code>${esc(key)}</code>\n` +
      `Сумма: ${sp.total_amount} ⭐\nПокупатель: @${esc(msg.from?.username || '—')} (id ${msg.from?.id || 0})`);
    return;
  }

  const dur = (payload.split(':')[1] || '1m');
  if (!DURATIONS[dur]) return;

  const k = genKey();
  const created = now();
  const expires = created + DURATIONS[dur] * 86400;

  await env.DB.prepare('INSERT INTO keys(key,duration,created,expires,status) VALUES(?,?,?,?,?)')
    .bind(k, dur, created, expires, 'unused').run();

  await env.DB.prepare(
    'INSERT INTO sales(key,duration,stars,tg_user,tg_username,ts,charge_id) VALUES(?,?,?,?,?,?,?)')
    .bind(k, dur, sp.total_amount || 0, msg.from?.id || 0, msg.from?.username || '',
          created, sp.telegram_payment_charge_id || '').run().catch(() => {});

  await tgSend(env.SHOPBOT_TOKEN, msg.chat.id,
    '<b>Оплата получена!</b>\n\n' +
    `Ваш ключ: <code>${esc(k)}</code>\n` +
    `Срок: ${DUR_LABEL[dur]} (до ${fmt(expires, env, true)})\n\n` +
    'Введите его в программе JavaDecompiler.exe и нажмите «Активировать».');

  await tgSend(env.KEYBOT_TOKEN || env.HWIDBOT_TOKEN, env.ADMIN_CHAT_ID,
    '<b>💰 Новая продажа</b>\n' +
    `Ключ: <code>${esc(k)}</code>\nСрок: ${DUR_LABEL[dur]}\n` +
    `Сумма: ${sp.total_amount} ⭐\nПокупатель: @${esc(msg.from?.username || msg.from?.id || '—')}`);

  await notifySecurity(env,
    `<b>💰 Продажа</b>\nКлюч: <code>${esc(k)}</code>\n` +
    `Срок: ${DUR_LABEL[dur]}\nСумма: ${sp.total_amount} ⭐\n` +
    `Покупатель: @${esc(msg.from?.username || '—')} (id ${msg.from?.id || 0})`);
}

async function tgShop(req, env) {
  const update = await readUpdate(req, env, 'SHOPBOT_WEBHOOK_SECRET');
  if (!update) return ok();

  // Подтверждение оплаты ДО списания — обязательный шаг Telegram.
  if (update.pre_checkout_query) {
    await tgApi(env.SHOPBOT_TOKEN, 'answerPreCheckoutQuery', {
      pre_checkout_query_id: update.pre_checkout_query.id, ok: true,
    });
    return ok();
  }

  if (update.callback_query) {
    const cq = update.callback_query;
    const data = cq.data || '';
    if (data.startsWith('buy:')) {
      await tgApi(env.SHOPBOT_TOKEN, 'answerCallbackQuery', { callback_query_id: cq.id });
      await shopInvoice(env, cq.message?.chat?.id || cq.from?.id, data.split(':')[1]);
    } else if (data === 'reset') {
      await tgApi(env.SHOPBOT_TOKEN, 'answerCallbackQuery', { callback_query_id: cq.id });
      await tgSend(env.SHOPBOT_TOKEN, cq.message?.chat?.id || cq.from?.id,
        '<b>Сброс HWID</b>\n\n' +
        'Отвязывает ключ от текущего компьютера, чтобы активировать его на другом.\n' +
        'Срок подписки при этом не меняется.\n\n' +
        `Стоимость: <b>${resetPrice(env)}</b> ⭐\n\n` +
        'Отправьте ключ командой:\n<code>/reset ВАШ-КЛЮЧ</code>');
    } else {
      await tgApi(env.SHOPBOT_TOKEN, 'answerCallbackQuery', { callback_query_id: cq.id });
    }
    return ok();
  }

  const msg = update.message || update.edited_message;
  if (!msg) return ok();

  if (msg.successful_payment) {
    try { await shopPaid(env, msg); }
    catch (e) { await notifySecurity(env, `<b>⚠️ Ошибка выдачи ключа</b>\n${esc(e && e.message || e)}`); }
    return ok();
  }

  if (!msg.text) return ok();
  const { cmd, args } = parse(msg.text);
  const chat = msg.chat.id;

  if (cmd === '/start' || cmd === '/buy' || cmd === '/price') {
    await shopMenu(env, chat);
    return ok();
  }
  if (cmd === '/help') {
    await tgSend(env.SHOPBOT_TOKEN, chat,
      'Команды:\n/buy — тарифы и покупка\n/reset — сброс HWID\n/mykeys — мои ключи\n/help — помощь\n\n' +
      'После оплаты ключ придёт в этот чат.');
    return ok();
  }
  if (cmd === '/reset') {
    const key = (args[0] || '').trim().toUpperCase();
    if (!key) {
      await tgSend(env.SHOPBOT_TOKEN, chat,
        `Отправьте ключ: <code>/reset ВАШ-КЛЮЧ</code>\nСтоимость сброса — ${resetPrice(env)} ⭐`);
      return ok();
    }
    const row = await env.DB.prepare('SELECT key,hwid,owner,status FROM keys WHERE key=?').bind(key).first();
    if (!row) {
      await tgSend(env.SHOPBOT_TOKEN, chat, 'Ключ не найден. Проверьте написание.');
      return ok();
    }
    if (!ownsKey(row, chat)) {
      await tgSend(env.SHOPBOT_TOKEN, chat,
        'Этот ключ закреплён за другим аккаунтом. Сброс HWID доступен только владельцу ключа.');
      return ok();
    }
    if (!row.hwid) {
      await tgSend(env.SHOPBOT_TOKEN, chat, 'Этот ключ ещё не привязан к компьютеру — сброс не нужен.');
      return ok();
    }
    await shopResetInvoice(env, chat, key);
    return ok();
  }
  if (cmd === '/mykeys' || cmd === '/key') {
    const { results } = await env.DB.prepare(
      'SELECT key,duration,expires,status,hwid FROM keys WHERE owner=? ORDER BY created DESC LIMIT 10')
      .bind(String(chat)).all();
    if (!results.length) {
      await tgSend(env.SHOPBOT_TOKEN, chat, 'У вас пока нет ключей. /buy');
    } else {
      await tgSend(env.SHOPBOT_TOKEN, chat, results.map(r =>
        `<code>${esc(r.key)}</code> — ${esc(DUR_LABEL[r.duration] || r.duration)}, до ${fmt(r.expires, env)}` +
        (r.hwid ? ' (активирован)' : ' (не активирован)')).join('\n'));
    }
    return ok();
  }

  return ok();
}

// ---------------- Обновления (R2) ----------------

async function versionInfo(env) {
  if (!env.JAR) return json({ error: 'no_bucket_binding' }, 500);

  // Подписанный манифест (создаётся sign-manifest.ps1) отдаём как есть.
  const man = await env.JAR.get('manifest.json');
  if (man) {
    const text = await man.text();
    return new Response(text, {
      headers: {
        'content-type': 'application/json; charset=utf-8',
        'cache-control': 'no-store',
      },
    });
  }

  const obj = await env.JAR.get('latest.jar');
  if (!obj) return json({ error: 'no_jar' }, 404);
  const buf = await obj.arrayBuffer();
  const digest = await crypto.subtle.digest('SHA-256', buf);
  const sha = Array.from(new Uint8Array(digest))
    .map((b) => b.toString(16).padStart(2, '0')).join('');
  const meta = obj.customMetadata || {};
  let version = meta.version || '';
  if (!version) {
    const vt = await env.JAR.get('version.txt');
    if (vt) version = (await vt.text()).trim();
  }
  return json({
    jar_version: version || 'unknown',
    sha256: sha,
    size: buf.byteLength,
    url: '/latest.jar',
    min_loader: meta.min_loader || '',
  });
}

async function latestJar(env) {
  if (!env.JAR) return new Response('no bucket binding', { status: 500 });
  const obj = await env.JAR.get('latest.jar');
  if (!obj) return new Response('no jar', { status: 404 });
  return new Response(obj.body, {
    headers: {
      'content-type': 'application/java-archive',
      'content-length': String(obj.size),
      'cache-control': 'public, max-age=300',
      'etag': obj.httpEtag || '',
    },
  });
}

// ---------------- router ----------------

export default {
  async fetch(req, env, ctx) {
    const url = new URL(req.url);
    try {
      if (url.pathname === '/') {
        return json({ ok: true, service: 'java-decompiler-license', version: '1.3', ts: now() });
      }
      if (url.pathname === '/activate') return await activate(url, env, ctx);
      if (url.pathname === '/report') return await report(url, env, ctx);
      if (url.pathname === '/version') return await versionInfo(env);
      if (url.pathname === '/latest.jar') return await latestJar(env);

      if (url.pathname === '/tg/admin' || url.pathname === '/tg/key' || url.pathname === '/tg/users') {
        return await tgAdmin(req, env);
      }
      if (url.pathname === '/tg/shop' || url.pathname === '/tg/buy') {
        return await tgShop(req, env);
      }
      return json({ error: 'not_found' }, 404);
    } catch (e) {
      return json({ status: 'server_error', error: String(e && e.message || e) }, 500);
    }
  },
};
