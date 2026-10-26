// Upstash Redis over its REST API (free tier; connect it to the Vercel project from Storage →
// Marketplace → Upstash, which sets the env vars below). Plain fetch, no dependencies.
// Files starting with "_" aren't routes on Vercel.

// Standard names first; then any prefix chosen when connecting the database in Vercel
// (e.g. STORAGE_KV_REST_API_URL / STORAGE_KV_REST_API_TOKEN).
function redisConfig() {
  const env = process.env;
  let url = env.KV_REST_API_URL || env.UPSTASH_REDIS_REST_URL;
  let token = env.KV_REST_API_TOKEN || env.UPSTASH_REDIS_REST_TOKEN;
  if (!url || !token) {
    for (const key of Object.keys(env)) {
      const m = /^(.*)(REST_API_URL|REDIS_REST_URL)$/.exec(key);
      if (!m) continue;
      const tokenKey = m[1] + m[2].replace('URL', 'TOKEN');
      if (env[key] && env[tokenKey]) {
        url = env[key];
        token = env[tokenKey];
        break;
      }
    }
  }
  return url && token ? { url: url.replace(/\/$/, ''), token } : null;
}

// Runs Redis commands in one round trip and returns each result.
async function pipeline(commands) {
  const config = redisConfig();
  if (!config) throw new Error('Download counting storage is not configured.');
  const res = await fetch(`${config.url}/pipeline`, {
    method: 'POST',
    headers: { Authorization: `Bearer ${config.token}`, 'Content-Type': 'application/json' },
    body: JSON.stringify(commands),
  });
  if (!res.ok) throw new Error(`Redis responded with HTTP ${res.status}.`);
  const results = await res.json();
  if (results.some((r) => r.error)) throw new Error('Redis command failed.');
  return results.map((r) => r.result);
}

// Only a two-letter country code and numbers are stored: no IP addresses, nothing about the person.
const KEYS = {
  countries: 'homeflix:downloads:countries', // country -> downloads
  platforms: 'homeflix:downloads:platforms', // windows / mac -> downloads
  total: 'homeflix:downloads:total',
};

module.exports = { redisConfig, pipeline, KEYS };
