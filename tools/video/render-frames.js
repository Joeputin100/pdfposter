// rc83: deterministic product-video frame renderer. Loads each animated
// segment page, pauses every animation, steps the clock 1/30s at a time and
// screenshots each frame. Runs on GitHub Actions (render-video.yml) using
// the runner's preinstalled Chrome via puppeteer-core — rendering locally
// pegs the workstation CPU for ~10 minutes, so CI owns this.
const puppeteer = require('puppeteer-core');
const fs = require('fs'), path = require('path');
const SEGS = [
  ['seg-1-title', 5.8], ['seg-2-pick', 2.6], ['seg-3-size', 3.7], ['seg-4-assy', 7.0],
  ['seg-5-models', 5.2], ['seg-6-compare', 4.0], ['seg-7-gemini', 2.8], ['seg-7b-free', 6.8],
  ['seg-8-end', 4.0],
];
const FPS = 30;
(async () => {
  const browser = await puppeteer.launch({
    executablePath: process.env.PUPPETEER_EXECUTABLE_PATH || '/usr/bin/google-chrome',
    args: ['--no-sandbox', '--force-color-profile=srgb'],
  });
  const page = await browser.newPage();
  await page.setViewport({ width: 1920, height: 1080, deviceScaleFactor: 1 });
  const outRoot = path.join(__dirname, 'frames');
  fs.rmSync(outRoot, { recursive: true, force: true });
  fs.mkdirSync(outRoot, { recursive: true });
  let global = 0;
  for (const [name, dur] of SEGS) {
    await page.goto('file://' + path.join(__dirname, name + '.html'), { waitUntil: 'networkidle0' });
    await new Promise(r => setTimeout(r, 250));
    await page.evaluate(() => document.getAnimations({ subtree: true }).forEach(a => a.pause()));
    const frames = Math.round(dur * FPS);
    for (let f = 0; f < frames; f++) {
      const t = f * 1000 / FPS;
      await page.evaluate(t => document.getAnimations({ subtree: true }).forEach(a => { a.currentTime = t; }), t);
      await page.screenshot({ path: path.join(outRoot, `f${String(global).padStart(4, '0')}.jpg`), type: 'jpeg', quality: 90 });
      global++;
    }
    console.log(name, frames, 'frames, total', global);
  }
  await browser.close();
  console.log('TOTAL FRAMES', global);
})();
