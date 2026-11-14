// Runs inside the HOMEFLIX Android app: starts the same server.js the desktop app uses, with the
// settings the app wrote to config.json. Every few seconds it passes the phone's heat state
// (heat.json, written by the app) to the server and reports back how it's doing (status.json).
// argv[2]: the app's data folder.

const fs = require('fs');
const path = require('path');
const server = require('./server.js');

const dataDir = process.argv[2];
const config = JSON.parse(fs.readFileSync(path.join(dataDir, 'config.json'), 'utf8'));
server.configure(config);

let port = null;
let error = null;

function writeStatus() {
  let paused = null;
  try {
    paused = JSON.parse(fs.readFileSync(path.join(dataDir, 'heat.json'), 'utf8')).reason || null;
  } catch {}
  server.setPaused(paused);
  server.cacheSize().catch(() => 0).then((cacheBytes) => {
    const file = path.join(dataDir, 'status.json');
    fs.writeFileSync(`${file}.tmp`, JSON.stringify({ running: !!port, port, error, cacheBytes, paused, at: Date.now() }));
    fs.renameSync(`${file}.tmp`, file);
  });
}

// Keep the saved port so bookmarks keep working; step up if something else holds it.
async function start() {
  for (let p = config.port; p < config.port + 20; p++) {
    server.configure({ port: p });
    try {
      port = await server.start();
      console.log(`HOMEFLIX is serving on port ${port}`);
      server.scanLibrary().then((m) => console.log(`Library: ${m.length} video(s)`));
      return;
    } catch (err) {
      if (err.code !== 'EADDRINUSE') {
        error = err.message;
        return;
      }
    }
  }
  error = `Ports ${config.port}–${config.port + 19} are all in use.`;
}

start().finally(writeStatus);
setInterval(writeStatus, 3000);
