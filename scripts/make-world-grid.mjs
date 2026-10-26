// Builds docs/world-grid.json: the world as a grid of squares (like GitHub's contribution graph), each
// land square tagged with its country's two-letter code. The website colours these squares by download
// counts. Run once: npm run world-grid (the result is committed; it only changes if this script does).
//
// Same approach as Ladderly's map: Natural Earth projection of the 110m world map, one square per cell,
// and tiny countries without a square of their own get the square at their centre.

import fs from 'node:fs';
import path from 'node:path';
import { fileURLToPath } from 'node:url';
import { geoCentroid, geoContains, geoNaturalEarth1 } from 'd3-geo';
import { feature } from 'topojson-client';
import countries from 'i18n-iso-countries';
import world from 'world-atlas/countries-110m.json' with { type: 'json' };

const COLS = 120;
const ROWS = 56;
const root = path.resolve(path.dirname(fileURLToPath(import.meta.url)), '..');

// Small countries and territories missing from the low-detail map, as [longitude, latitude].
const SMALL_PLACES = {
  SG: [103.82, 1.35], HK: [114.17, 22.32], MO: [113.54, 22.2], BH: [50.55, 26.07], MT: [14.38, 35.94], MV: [73.22, 3.2],
  MU: [57.55, -20.35], SC: [55.49, -4.68], AD: [1.52, 42.51], MC: [7.42, 43.74], LI: [9.55, 47.17], SM: [12.46, 43.94],
  BB: [-59.54, 13.19], CV: [-23.6, 15.12], KM: [43.87, -11.88], ST: [6.61, 0.19], TO: [-175.2, -21.18], WS: [-172.1, -13.76],
};

const shapes = feature(world, world.objects.countries).features
  .map((f) => ({ f, code: countries.numericToAlpha2(String(f.id).padStart(3, '0')) }))
  .filter((s) => s.code);
const projection = geoNaturalEarth1().fitSize([COLS, ROWS], { type: 'FeatureCollection', features: shapes.map((s) => s.f) });

const cells = new Map(); // "col,row" -> code
const centres = {};
const seen = new Set();

for (let row = 0; row < ROWS; row++) {
  for (let col = 0; col < COLS; col++) {
    const lonLat = projection.invert([col + 0.5, row + 0.5]);
    if (!lonLat || !Number.isFinite(lonLat[0])) continue;
    // Squares outside the map's oval outline "un-project" to longitudes past ±180°, which would wrap
    // round onto the far side of the world. Keep only points that project back to this square.
    const back = projection(lonLat);
    if (Math.abs(lonLat[0]) > 180 || Math.abs(lonLat[1]) > 90 || !back || Math.hypot(back[0] - col - 0.5, back[1] - row - 0.5) > 0.01) continue;
    const hit = shapes.find((s) => geoContains(s.f, lonLat));
    if (!hit) continue;
    cells.set(`${col},${row}`, hit.code);
    seen.add(hit.code);
  }
}

const place = (code, lonLat) => {
  const p = projection(lonLat);
  if (!p) return;
  const col = Math.min(COLS - 1, Math.max(0, Math.floor(p[0])));
  const row = Math.min(ROWS - 1, Math.max(0, Math.floor(p[1])));
  centres[code] = [col, row];
  if (!seen.has(code)) {
    // A country too small for a square of its own takes the square at its centre.
    cells.set(`${col},${row}`, code);
    seen.add(code);
  }
};
for (const s of shapes) place(s.code, geoCentroid(s.f));
for (const [code, lonLat] of Object.entries(SMALL_PLACES)) if (!centres[code]) place(code, lonLat);

const out = {
  cols: COLS,
  rows: ROWS,
  // [col, row, country] per land square
  cells: [...cells.entries()].map(([k, code]) => [...k.split(',').map(Number), code]),
  // where each country's colour spreads from
  centres,
};
fs.writeFileSync(path.join(root, 'docs', 'world-grid.json'), JSON.stringify(out));
console.log(`world-grid.json: ${out.cells.length} land squares, ${Object.keys(centres).length} countries`);
