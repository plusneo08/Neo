// Gemini API keys: any number stored, exactly one active. Encrypted at rest (AES-256-GCM) when KEYS_SECRET is set.
import fs from 'node:fs';
import path from 'node:path';
import crypto from 'node:crypto';

const MAX_KEYS = 50;
const mask = k => (k.length > 10 ? `${k.slice(0, 4)}…${k.slice(-4)}` : '••••');

export class KeyStore {
  constructor(file, secret) {
    this.file = file; this.secret = secret || ''; this.salt = null;
    this.state = { active: null, keys: [] };
    this.#load();
  }

  #load() {
    if (!fs.existsSync(this.file)) return;
    const f = JSON.parse(fs.readFileSync(this.file, 'utf8'));
    if (f.enc) {
      if (!this.secret) throw new Error('keys file is encrypted but KEYS_SECRET is not set');
      this.salt = Buffer.from(f.salt, 'base64');
      const d = crypto.createDecipheriv('aes-256-gcm', this.#derive(), Buffer.from(f.iv, 'base64'));
      d.setAuthTag(Buffer.from(f.tag, 'base64'));
      this.state = JSON.parse(Buffer.concat([d.update(Buffer.from(f.data, 'base64')), d.final()]).toString('utf8'));
    } else this.state = f.state;
  }

  #derive() { return crypto.scryptSync(this.secret, this.salt, 32); }

  #save() {
    fs.mkdirSync(path.dirname(this.file), { recursive: true });
    let out;
    if (this.secret) {
      this.salt ??= crypto.randomBytes(16);
      const iv = crypto.randomBytes(12);
      const c = crypto.createCipheriv('aes-256-gcm', this.#derive(), iv);
      const data = Buffer.concat([c.update(JSON.stringify(this.state), 'utf8'), c.final()]);
      out = { v: 1, enc: true, salt: this.salt.toString('base64'), iv: iv.toString('base64'), tag: c.getAuthTag().toString('base64'), data: data.toString('base64') };
    } else out = { v: 1, enc: false, state: this.state };
    const tmp = this.file + '.tmp';
    fs.writeFileSync(tmp, JSON.stringify(out), { mode: 0o600 });
    fs.renameSync(tmp, this.file);
  }

  /** Never includes the key itself. */
  list() {
    return this.state.keys.map(k => ({ id: k.id, label: k.label, masked: mask(k.key), active: k.id === this.state.active, addedAt: k.addedAt }));
  }
  activeKey() { return this.state.keys.find(k => k.id === this.state.active)?.key ?? null; }
  get size() { return this.state.keys.length; }

  add(label, key) {
    key = String(key ?? '').trim(); label = String(label ?? '').trim().slice(0, 40) || 'key';
    if (key.length < 10 || key.length > 200 || /\s/.test(key)) throw new KeyError(400, 'that does not look like an API key');
    if (this.state.keys.length >= MAX_KEYS) throw new KeyError(400, `limit of ${MAX_KEYS} keys reached`);
    if (this.state.keys.some(k => k.key === key)) throw new KeyError(409, 'that key is already stored');
    const id = crypto.randomBytes(6).toString('hex');
    this.state.keys.push({ id, label, key, addedAt: Date.now() });
    this.#save();
    return id;
  }

  /** Makes exactly one key active; all others become inactive. */
  activate(id) {
    if (!this.state.keys.some(k => k.id === id)) throw new KeyError(404, 'no such key');
    this.state.active = id; this.#save();
  }

  remove(id) {
    if (!this.state.keys.some(k => k.id === id)) throw new KeyError(404, 'no such key');
    if (this.state.active === id) throw new KeyError(409, 'that key is active; activate another one first');
    this.state.keys = this.state.keys.filter(k => k.id !== id); this.#save();
  }
}

export class KeyError extends Error { constructor(status, message) { super(message); this.status = status; } }
