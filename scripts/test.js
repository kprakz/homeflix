// HOMEFLIX CI & Security Integration Test Suite
// Verifies HTTP serving, FFmpeg transcoding, security headers, path traversal, DNS rebinding, CSRF, and PIN rate limiting.

const fs = require('fs');
const path = require('path');
const http = require('http');
const assert = require('assert');
const { execFileSync } = require('child_process');
const homeflix = require('../server');

async function runCiSuite() {
  console.log('=== STARTING HOMEFLIX CI & SECURITY TEST SUITE ===');

  const testDir = path.resolve('./cache/ci-test-movies');
  fs.mkdirSync(testDir, { recursive: true });
  const testMovie = path.join(testDir, 'Test Movie (2024).mkv');

  const ffmpegBin = fs.existsSync(path.resolve('vendor/ffmpeg/ffmpeg'))
    ? path.resolve('vendor/ffmpeg/ffmpeg')
    : 'ffmpeg';
  const ffprobeBin = fs.existsSync(path.resolve('vendor/ffmpeg/ffprobe'))
    ? path.resolve('vendor/ffmpeg/ffprobe')
    : 'ffprobe';

  console.log('1. Generating synthetic test movie with FFmpeg...');
  execFileSync(ffmpegBin, [
    '-v', 'error', '-y',
    '-f', 'lavfi', '-i', 'testsrc2=size=1280x720:rate=24:duration=15',
    '-f', 'lavfi', '-i', 'sine=frequency=440:duration=15',
    '-c:v', 'libx264', '-pix_fmt', 'yuv420p',
    '-c:a', 'aac', '-shortest',
    testMovie,
  ]);
  const past = new Date(Date.now() - 500000);
  fs.utimesSync(testMovie, past, past);

  console.log('2. Configuring & launching HOMEFLIX server...');
  const port = 18090;
  homeflix.configure({
    port,
    folders: [testDir],
    cacheDir: 'cache/ci-cache',
    ffmpeg: ffmpegBin,
    ffprobe: ffprobeBin,
  });

  await homeflix.start();
  console.log(`Server started on http://127.0.0.1:${port}`);

  try {
    // 3. Web Page & Security Headers Check
    console.log('3. Testing GET / and security headers...');
    const resRoot = await fetch(`http://127.0.0.1:${port}/`);
    assert.strictEqual(resRoot.status, 200, 'Root status 200');
    const html = await resRoot.text();
    assert.ok(html.includes('HOMEFLIX'), 'HTML contains HOMEFLIX');
    assert.strictEqual(resRoot.headers.get('x-content-type-options'), 'nosniff');
    assert.strictEqual(resRoot.headers.get('x-frame-options'), 'DENY');
    assert.strictEqual(resRoot.headers.get('referrer-policy'), 'strict-origin-when-cross-origin');
    assert.strictEqual(resRoot.headers.get('cross-origin-resource-policy'), 'same-origin');
    assert.ok(resRoot.headers.get('content-security-policy'), 'CSP header present');
    console.log('   ✓ Root page and security headers verified');

    // 4. Library Scan
    console.log('4. Testing GET /api/library...');
    const resLib = await fetch(`http://127.0.0.1:${port}/api/library`);
    assert.strictEqual(resLib.status, 200);
    const library = await resLib.json();
    assert.ok(Array.isArray(library) && library.length > 0, 'Library has movies');
    const movie = library.find((m) => m.title.includes('Test Movie'));
    assert.ok(movie, 'Found Test Movie in library');
    const id = movie.id;
    console.log(`   ✓ Library scanned successfully, Movie ID: ${id}`);

    // 5. Info probe
    console.log('5. Testing GET /api/info/:id...');
    const resInfo = await fetch(`http://127.0.0.1:${port}/api/info/${id}`);
    assert.strictEqual(resInfo.status, 200);
    const info = await resInfo.json();
    assert.ok(info.duration > 0, 'Duration > 0');
    assert.strictEqual(info.videoCodec, 'h264');
    console.log(`   ✓ Probed media info: ${info.videoCodec}, ${info.duration.toFixed(1)}s, mode: ${info.mode}`);

    // 6. HLS Segment generation
    console.log('6. Testing HLS segment stream /hls/:id/0.ts...');
    const resHls = await fetch(`http://127.0.0.1:${port}/hls/${id}/0.ts`);
    assert.strictEqual(resHls.status, 200, 'HLS segment status 200');
    const segBuf = Buffer.from(await resHls.arrayBuffer());
    assert.ok(segBuf.length > 1000, 'Segment size > 1KB');
    console.log(`   ✓ HLS segment streamed (${segBuf.length} bytes)`);

    // 7. Video Prepare (Remux / transcode copy)
    console.log('7. Testing POST /api/prepare/:id & status polling...');
    const resPrep = await fetch(`http://127.0.0.1:${port}/api/prepare/${id}`, { method: 'POST' });
    assert.strictEqual(resPrep.status, 200);
    let ready = false;
    for (let i = 0; i < 30; i++) {
      const resSt = await fetch(`http://127.0.0.1:${port}/api/status/${id}`);
      const st = await resSt.json();
      if (st.state === 'ready') { ready = true; break; }
      await new Promise((r) => setTimeout(r, 500));
    }
    assert.ok(ready, 'Prepared copy reached state: ready');
    console.log('   ✓ Prepared copy is ready');

    // 8. Path Traversal Defense
    console.log('8. Testing Path Traversal blocking (GET /..%2fpackage.json)...');
    const travStatus = await new Promise((resolve) => {
      http.get({ host: '127.0.0.1', port, path: '/..%2fpackage.json' }, (res) => resolve(res.statusCode));
    });
    assert.strictEqual(travStatus, 403, 'Path traversal must return 403');
    console.log('   ✓ Path traversal blocked (403 Forbidden)');

    // 9. DNS Rebinding / Host Header Protection
    console.log('9. Testing DNS Rebinding defense (Host: evil.example.com)...');
    const hostStatus = await new Promise((resolve) => {
      http.get({ host: '127.0.0.1', port, path: '/api/library', headers: { Host: 'evil.example.com' } }, (res) => resolve(res.statusCode));
    });
    assert.strictEqual(hostStatus, 403, 'Evil host must return 403');
    console.log('   ✓ External Host rejected (403 Forbidden)');

    // 10. CSRF Protection
    console.log('10. Testing CSRF blocking on POST /api/prepare/:id...');
    const csrfRes = await fetch(`http://127.0.0.1:${port}/api/prepare/${id}`, {
      method: 'POST',
      headers: {
        Origin: 'http://malicious-website.com',
        'Sec-Fetch-Site': 'cross-site',
      },
    });
    assert.strictEqual(csrfRes.status, 403, 'Cross-site POST must return 403');
    console.log('   ✓ Cross-site request rejected (403 Forbidden)');

    // 11. PIN Authentication & Rate Limiting
    console.log('11. Testing PIN Authentication & Brute Force Rate Limiting...');
    homeflix.configure({ pin: '4321' });

    // Request library without PIN -> 401
    const unauthRes = await fetch(`http://127.0.0.1:${port}/api/library`);
    assert.strictEqual(unauthRes.status, 401, 'Unauthenticated library request returns 401');

    // Wrong PIN -> 401
    const wrongPinRes = await fetch(`http://127.0.0.1:${port}/api/unlock`, {
      method: 'POST',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify({ pin: '0000' }),
    });
    assert.strictEqual(wrongPinRes.status, 401, 'Wrong PIN returns 401');

    // Correct PIN -> 200 + sets cookie
    const rightPinRes = await fetch(`http://127.0.0.1:${port}/api/unlock`, {
      method: 'POST',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify({ pin: '4321' }),
    });
    assert.strictEqual(rightPinRes.status, 200, 'Correct PIN returns 200');
    const cookie = rightPinRes.headers.get('set-cookie');
    assert.ok(cookie && cookie.includes('homeflix_key='), 'Sets homeflix_key cookie');

    // Request library with authenticated cookie -> 200
    const authLibRes = await fetch(`http://127.0.0.1:${port}/api/library`, {
      headers: { Cookie: cookie.split(';')[0] },
    });
    assert.strictEqual(authLibRes.status, 200, 'Authenticated library request returns 200');
    console.log('   ✓ PIN authentication and session cookies verified');

    // Test Brute Force Rate Limiting: 5 failed attempts trigger lock, 6th attempt receives 429
    for (let i = 0; i < 5; i++) {
      const r = await fetch(`http://127.0.0.1:${port}/api/unlock`, {
        method: 'POST',
        headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify({ pin: '9999' }),
      });
      assert.strictEqual(r.status, 401, `Attempt ${i + 1} fails with 401`);
    }
    const rateLimitedRes = await fetch(`http://127.0.0.1:${port}/api/unlock`, {
      method: 'POST',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify({ pin: '9999' }),
    });
    assert.strictEqual(rateLimitedRes.status, 429, 'Subsequent attempt while locked returns 429');
    console.log('   ✓ PIN brute force throttling verified (429 Rate Limited)');

    // 12. Static Assets Check
    console.log('12. Testing static assets and vendor distribution...');
    const resVendor = await fetch(`http://127.0.0.1:${port}/vendor/hls.min.js`);
    assert.strictEqual(resVendor.status, 200);
    const resManifest = await fetch(`http://127.0.0.1:${port}/manifest.webmanifest`);
    assert.strictEqual(resManifest.status, 200);
    console.log('   ✓ Vendor & PWA assets verified');

    console.log('\n=======================================================');
    console.log('  ALL CI & SECURITY INTEGRATION TESTS PASSED (12/12)!');
    console.log('=======================================================\n');
  } finally {
    await homeflix.stop();
    fs.rmSync(testDir, { recursive: true, force: true });
    fs.rmSync('cache/ci-cache', { recursive: true, force: true });
  }
}

runCiSuite().catch((err) => {
  console.error('CI TEST FAILED:', err);
  process.exit(1);
});
