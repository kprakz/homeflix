const { test } = require('node:test');
const assert = require('node:assert/strict');
const fs = require('fs');
const os = require('os');
const path = require('path');
const server = require('../server');

function write(root, relative, bytes = 1) {
  const file = path.join(root, relative);
  fs.mkdirSync(path.dirname(file), { recursive: true });
  fs.writeFileSync(file, Buffer.alloc(bytes));
}

test('movie and TV series folders produce the right library metadata', async (t) => {
  const root = fs.mkdtempSync(path.join(os.tmpdir(), 'homeflix-library-'));
  t.after(() => fs.rmSync(root, { recursive: true, force: true }));

  const movies = path.join(root, 'Movies');
  const breakingBad = path.join(root, 'Breaking Bad');
  const office = path.join(root, 'The Office');
  write(movies, 'Heat (1995).mp4');
  write(movies, 'Show.S01E05.mp4');
  write(breakingBad, 'Season 1/Episode 02.mp4');
  write(breakingBad, 'Season 1/Episode 10.mp4');
  write(breakingBad, 'Season 2/Pilot.mkv');
  write(office, '01 - Pilot.mp4');
  write(office, '02 - Diversity Day.mp4');

  server.configure({
    folders: [
      { path: movies, type: 'movies' },
      { path: breakingBad, type: 'series' },
      { path: office, type: 'series' },
    ],
    minSizeMB: 0,
    cacheDir: path.join(root, 'cache'),
    dataDir: path.join(root, 'data'),
  });

  const items = await server.scanLibrary();
  const movie = items.find((item) => item.title === 'Heat');
  assert.equal(movie.title, 'Heat');
  assert.equal(movie.year, '1995');
  assert.equal(movie.folderType, 'movies');
  assert.equal(movie.show, undefined);

  const movieWithSeriesName = items.find((item) => item.title === 'Show S01E05');
  assert.equal(movieWithSeriesName.folderType, 'movies');
  assert.equal(movieWithSeriesName.show, undefined);

  const bb = items.filter((item) => item.show === 'Breaking Bad').sort((a, b) => a.season - b.season || a.ep - b.ep);
  assert.deepEqual(bb.map((item) => [item.season, item.ep]), [[1, 2], [1, 10], [2, 1]]);
  assert.equal(bb[2].title, 'Pilot');

  const officeEpisodes = items.filter((item) => item.show === 'The Office').sort((a, b) => a.ep - b.ep);
  assert.deepEqual(officeEpisodes.map((item) => [item.season, item.ep, item.title]), [
    [1, 1, 'Pilot'],
    [1, 2, 'Diversity Day'],
  ]);
});

test('legacy folder paths migrate to movie folders', () => {
  const folders = server.normalizeFolders(['/tmp/one', '/tmp/one', { path: '/tmp/two', type: 'series' }, { path: '/tmp/three', type: 'unknown' }]);
  assert.deepEqual(folders.map((folder) => [path.basename(folder.path), folder.type]), [
    ['one', 'movies'],
    ['two', 'series'],
    ['three', 'movies'],
  ]);
});

test('PIN validation and configure accept alphanumeric passwords and fail closed', () => {
  assert.equal(server.isValidPin('1234'), true);
  assert.equal(server.isValidPin('123456'), true);
  assert.equal(server.isValidPin('12345678'), true); // 4-8 digits family
  assert.equal(server.isValidPin('12345'), true); // 4-8 digits family
  assert.equal(server.isValidPin('Homeflix2026'), true);
  assert.equal(server.isValidPin('abcdefg'), false); // password needs a digit too
  assert.equal(server.isValidPin('password123'), true);
  assert.equal(server.isValidPin('has space1'), false);
  assert.equal(server.isValidPin('123'), false);
  assert.equal(server.isValidPin('123456789'), false);
  assert.equal(server.isValidPin(null), false);

  // a malformed PIN must be rejected, not silently turned into "no protection"
  assert.throws(() => server.configure({ pin: 'not valid' }));
  assert.throws(() => server.configure({ pin: true }));
  assert.throws(() => server.configure({ pin: { salt: 'abc' } }));
  // legit values pass: hashed object from the desktop app, null (protection off)
  server.configure({ pin: { salt: 'a'.repeat(16), hash: 'b'.repeat(64) } });
  server.configure({ pin: null });
});
