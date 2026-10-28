// GET /api/download?url=<GitHub release file> — counts the download for the visitor's country (from
// Vercel's geolocation header; no IP address or personal data is stored), once per browser, then
// redirects to the installer on GitHub. Only HOMEFLIX's own release files are allowed, so this can't
// be used to send people anywhere else. Counting problems never block the download.

const { redisConfig, pipeline, KEYS } = require('./_redis');

const RELEASES = 'https://github.com/kprakz/homeflix/releases/';
const FALLBACK = `${RELEASES}latest`;
const COUNTED_COOKIE = 'homeflix_dl';
// Crawlers and link previews shouldn't count.
const BOT = /bot|crawl|spider|slurp|preview|facebookexternalhit|whatsapp|curl|wget/i;

function platformOf(url) {
  if (/\.exe$/i.test(url)) return 'windows';
  if (/\.dmg$/i.test(url)) return 'mac';
  if (/\.(AppImage|deb)$/i.test(url)) return 'linux';
  return 'other';
}

module.exports = async (req, res) => {
  const requested = String((req.query && req.query.url) || '');
  const ok = requested.startsWith(`${RELEASES}download/`) || requested.startsWith(`${RELEASES}latest/download/`);
  const target = ok ? requested : FALLBACK;

  const alreadyCounted = /(?:^|;\s*)homeflix_dl=/.test(req.headers.cookie || '');
  const isBot = BOT.test(req.headers['user-agent'] || '');
  if (ok && !alreadyCounted && !isBot && redisConfig()) {
    const raw = String(req.headers['x-vercel-ip-country'] || '').trim().toUpperCase();
    const country = /^[A-Z]{2}$/.test(raw) ? raw : 'XX';
    try {
      await pipeline([
        ['HINCRBY', KEYS.countries, country, 1],
        ['HINCRBY', KEYS.platforms, platformOf(target), 1],
        ['INCR', KEYS.total],
      ]);
      res.setHeader('Set-Cookie', `${COUNTED_COOKIE}=1; Max-Age=31536000; Path=/; HttpOnly; Secure; SameSite=Lax`);
    } catch (err) {
      console.error('Could not record download:', err.message);
    }
  }
  res.setHeader('Cache-Control', 'no-store');
  res.statusCode = 302;
  res.setHeader('Location', target);
  res.end();
};
