// NEO PLUS backend: keeps the Gemini key off the phone, authenticates the app, rate-limits.
import express from 'express';
import rateLimit from 'express-rate-limit';
import crypto from 'node:crypto';
import { KeyStore, KeyError } from './keystore.js';

const {
  GEMINI_API_KEY = '', KEYS_FILE = './data/keys.json', KEYS_SECRET = '', ADMIN_TOKEN = '',
  GEMINI_BASE_URL = 'https://generativelanguage.googleapis.com', GEMINI_MODEL = 'gemini-2.5-flash', ALLOWED_MODELS = GEMINI_MODEL,
  AUTH_MODE = 'token', DEVICE_TOKENS = '', PORT = 8080,
} = process.env;

if (!KEYS_SECRET) console.warn('WARNING: KEYS_SECRET not set, API keys are stored unencrypted on disk');
const keys = new KeyStore(KEYS_FILE, KEYS_SECRET);
if (keys.size === 0 && GEMINI_API_KEY) { keys.activate(keys.add('env', GEMINI_API_KEY)); }
if (!keys.activeKey()) console.warn('No active Gemini key yet. Add one through the app (Settings > API keys) or the admin API.');
if (!ADMIN_TOKEN) console.warn('ADMIN_TOKEN not set: key management endpoints are disabled.');
const allowedModels = new Set(ALLOWED_MODELS.split(',').map(s => s.trim()).filter(Boolean));
const sha = s => crypto.createHash('sha256').update(s).digest();
const tokenHashes = DEVICE_TOKENS.split(',').map(s => s.trim()).filter(Boolean).map(sha);
if (AUTH_MODE === 'token' && tokenHashes.length === 0) { console.error('DEVICE_TOKENS is required in token mode'); process.exit(1); }

let fbAuth = null;
if (AUTH_MODE === 'firebase') {
  const admin = (await import('firebase-admin')).default;
  admin.initializeApp();
  fbAuth = admin.auth();
}

const SYSTEM_PROMPT = `You are NEO, a personal assistant running on the user's Android phone.
Use the provided tools to act on the phone. Rules:
- Never claim an action succeeded unless the tool result status is VERIFIED. HANDED_OFF means the request was passed to Android/another app and is NOT confirmed; say so.
- If status is NEEDS_PERMISSION, UNSUPPORTED, DECLINED or FAILED, report that plainly and suggest the supported alternative.
- Do not invent tools, contacts, numbers or device data. Ask a short question if a required detail is missing.
- Paired devices (Windows PCs) are controlled only with the list_devices / pc_* / disconnect_device tools. Never say a PC action happened unless the result status is VERIFIED; pc_lock and media keys return HANDED_OFF, so say they were requested, not confirmed. If no device is paired, say so and point to the Devices tab.
- If a request needs something no tool provides (smart-home lights, TV, files on the PC, another Android phone), say that is not supported yet. Do not improvise.
- Reply in the language the user wrote or spoke in. Keep spoken-style replies brief.`;

async function authenticate(req, res, next) {
  const m = /^Bearer (.+)$/.exec(req.get('authorization') || '');
  if (!m) return res.status(401).json({ error: 'missing token' });
  try {
    if (AUTH_MODE === 'firebase') {
      const decoded = await fbAuth.verifyIdToken(m[1]);
      req.identity = decoded.uid;
    } else {
      const h = sha(m[1]);
      if (!tokenHashes.some(t => crypto.timingSafeEqual(t, h))) return res.status(401).json({ error: 'invalid token' });
      req.identity = h.toString('hex').slice(0, 16);
    }
    next();
  } catch { res.status(401).json({ error: 'invalid token' }); }
}

const app = express();
app.set('trust proxy', 1);
app.use(express.json({ limit: '256kb' }));
app.get('/health', (_req, res) => res.json({ ok: true }));
app.use('/v1', authenticate, rateLimit({
  windowMs: 60_000, limit: 30, standardHeaders: true, legacyHeaders: false,
  keyGenerator: req => req.identity,
}));

const adminHash = ADMIN_TOKEN ? sha(ADMIN_TOKEN) : null;
function adminAuth(req, res, next) {
  if (!adminHash) return res.status(403).json({ error: 'key management disabled (ADMIN_TOKEN not set)' });
  const m = /^Bearer (.+)$/.exec(req.get('authorization') || '');
  if (!m || !crypto.timingSafeEqual(sha(m[1]), adminHash)) return res.status(401).json({ error: 'invalid admin token' });
  next();
}
const adminLimit = rateLimit({ windowMs: 60_000, limit: 20, standardHeaders: true, legacyHeaders: false });
const wrap = fn => async (req, res) => {
  try { await fn(req, res); } catch (e) {
    if (e instanceof KeyError) return res.status(e.status).json({ error: e.message });
    console.error('admin error', e.message); res.status(500).json({ error: 'server error' });
  }
};
/** Cheap check that Google accepts the key. Returns true / false / null (could not tell). */
async function verifyKey(key) {
  try {
    const r = await fetch(`${GEMINI_BASE_URL}/v1beta/models?pageSize=1`, { headers: { 'x-goog-api-key': key }, signal: AbortSignal.timeout(8000) });
    if (r.ok) return true;
    return (r.status === 400 || r.status === 401 || r.status === 403) ? false : null;
  } catch { return null; }
}
app.use('/admin', adminLimit, adminAuth);
app.get('/admin/keys', wrap((_req, res) => res.json({ keys: keys.list() })));
app.post('/admin/keys', wrap(async (req, res) => {
  const id = keys.add(req.body?.label, req.body?.key);
  const verified = await verifyKey(String(req.body.key).trim());
  console.log(`admin: key ${id} added (verified=${verified})`);
  res.status(201).json({ id, verified, keys: keys.list() });
}));
app.post('/admin/keys/:id/activate', wrap((req, res) => {
  keys.activate(req.params.id); console.log(`admin: key ${req.params.id} activated`);
  res.json({ keys: keys.list() });
}));
app.delete('/admin/keys/:id', wrap((req, res) => {
  keys.remove(req.params.id); console.log(`admin: key ${req.params.id} removed`);
  res.json({ keys: keys.list() });
}));

app.post('/v1/chat', async (req, res) => {
  const { contents, tools, model } = req.body ?? {};
  if (!Array.isArray(contents) || contents.length === 0 || contents.length > 80) return res.status(400).json({ error: 'bad contents' });
  const apiKey = keys.activeKey();
  if (!apiKey) return res.status(503).json({ error: 'no active API key; add one in Settings > API keys' });
  const useModel = model && allowedModels.has(model) ? model : GEMINI_MODEL;
  const body = { systemInstruction: { parts: [{ text: SYSTEM_PROMPT }] }, contents };
  if (Array.isArray(tools) && tools.length && JSON.stringify(tools).length < 50_000) body.tools = tools;
  try {
    const r = await fetch(`${GEMINI_BASE_URL}/v1beta/models/${encodeURIComponent(useModel)}:generateContent`, {
      method: 'POST',
      headers: { 'content-type': 'application/json', 'x-goog-api-key': apiKey },
      body: JSON.stringify(body),
      signal: AbortSignal.timeout(30_000),
    });
    const text = await r.text();
    if (!r.ok) { console.error('gemini error', r.status, text.slice(0, 500)); return res.status(502).json({ error: 'upstream error', status: r.status }); }
    res.type('json').send(text);
  } catch (e) { console.error('gemini fetch failed', e.message); res.status(504).json({ error: 'upstream unreachable' }); }
});

app.listen(PORT, () => console.log(`NEO PLUS backend on :${PORT} (auth=${AUTH_MODE}, model=${GEMINI_MODEL})`));
