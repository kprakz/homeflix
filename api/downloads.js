// GET /api/downloads — download totals per country and platform, for the website's map.
// { enabled, total, countries: { IN: 12, ... }, platforms: { windows: 9, mac: 3 } }
// enabled is false (with zeros) until Upstash Redis is connected. Readable from any site (the page is
// also served from GitHub Pages), cached briefly at the edge.

const { redisConfig, pipeline, KEYS } = require('./_redis');

function toCounts(flat) {
  const counts = {};
  if (Array.isArray(flat)) for (let i = 0; i + 1 < flat.length; i += 2) counts[String(flat[i])] = Number(flat[i + 1]) || 0;
  return counts;
}

module.exports = async (req, res) => {
  res.setHeader('Access-Control-Allow-Origin', '*');
  res.setHeader('Cache-Control', 'public, s-maxage=60, stale-while-revalidate=300');
  res.setHeader('Content-Type', 'application/json');
  const empty = { total: 0, countries: {}, platforms: {} };
  if (!redisConfig()) return res.end(JSON.stringify({ enabled: false, ...empty }));
  try {
    const [countries, platforms, total] = await pipeline([
      ['HGETALL', KEYS.countries],
      ['HGETALL', KEYS.platforms],
      ['GET', KEYS.total],
    ]);
    res.end(JSON.stringify({ enabled: true, total: Number(total) || 0, countries: toCounts(countries), platforms: toCounts(platforms) }));
  } catch (err) {
    console.error('Could not read download counts:', err.message);
    res.end(JSON.stringify({ enabled: true, ...empty }));
  }
};
