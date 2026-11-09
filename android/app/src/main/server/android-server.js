// Entry point for the Node runtime embedded in the Android app. It wraps the canonical server.js,
// so the phone serves exactly the same API as the desktop app. The control server stays alive while
// Node is loaded, which lets the Android service stop and start the HTTP server without restarting
// the whole app process.
const fs = require('fs');
const http = require('http');
const path = require('path');
const server = require('./server.js');

const CONTROL_PORT = 18081;
let serving = false;
let starting = false;
let stopping = null;

function config() {
  const file = path.join(__dirname, 'config.json');
  return JSON.parse(fs.readFileSync(file, 'utf8'));
}

function startServer() {
  if (stopping) {
    stopping.finally(startServer);
    return;
  }
  if (serving || starting) return;
  starting = true;
  server.start()
    .then((port) => {
      starting = false;
      serving = true;
      console.log(`HOMEFLIX_SERVER_READY ${port}`);
    })
    .catch((err) => {
      starting = false;
      serving = false;
      console.error(`HOMEFLIX_SERVER_ERROR ${err.message}`);
    });
}

function stopServer() {
  if (stopping) return;
  starting = false;
  stopping = server.stop()
    .then(() => {
      serving = false;
      stopping = null;
      console.log('HOMEFLIX_SERVER_STOPPED');
    })
    .catch((err) => {
      serving = false;
      stopping = null;
      console.error(`HOMEFLIX_SERVER_STOP_ERROR ${err.message}`);
    });
}

function reloadServer() {
  server.configure(config());
  if (!serving && !starting && !stopping) {
    startServer();
    return;
  }
  stopServer();
  startServer();
}

function status(res) {
  res.writeHead(200, { 'Content-Type': 'application/json', 'Cache-Control': 'no-store' });
  res.end(JSON.stringify({ control: true, serving, starting, stopping: !!stopping }));
}

const control = http.createServer((req, res) => {
  if (req.method === 'POST' && req.url === '/start') {
    startServer();
    status(res);
    return;
  }
  if (req.method === 'POST' && req.url === '/reload') {
    reloadServer();
    status(res);
    return;
  }
  if (req.method === 'POST' && req.url === '/reconfig') {
    server.configure(config());
    status(res);
    return;
  }
  if (req.method === 'POST' && req.url === '/shutdown') {
    stopServer();
    status(res);
    return;
  }
  if (req.method === 'GET' && req.url === '/status') {
    status(res);
    return;
  }
  if (req.method === 'GET' && req.url.startsWith('/activity')) {
    res.writeHead(200, { 'Content-Type': 'application/json', 'Cache-Control': 'no-store' });
    res.end(JSON.stringify(server.activityForControl ? server.activityForControl(req.url.slice('/activity'.length)) : []));
    return;
  }
  res.statusCode = 404;
  res.end('not found');
});
control.on('error', (err) => console.error(`control server: ${err.message}`));
control.listen(CONTROL_PORT, '127.0.0.1', () => {
  console.log(`HOMEFLIX_CONTROL_READY ${CONTROL_PORT}`);
});

process.on('SIGTERM', () => stopServer());
process.on('SIGINT', () => stopServer());

server.configure(config());
