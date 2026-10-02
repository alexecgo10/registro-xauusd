// Lee una captura de MT5 (móvil, Historial → Posiciones) y saca el resumen del periodo.
// El texto se reconoce en el propio móvil con Tesseract.js; la imagen no se envía a ningún sitio.

const TESS = 'https://cdn.jsdelivr.net/npm/tesseract.js@5/dist/tesseract.min.js';
let loading = null;
function loadTesseract() {
  if (window.Tesseract) return Promise.resolve(window.Tesseract);
  if (!loading) loading = new Promise((ok, ko) => {
    const s = document.createElement('script');
    s.src = TESS; s.onload = () => ok(window.Tesseract); s.onerror = () => { loading = null; ko(new Error('No se pudo cargar el lector de texto. ¿Tienes conexión?')); };
    document.head.appendChild(s);
  });
  return loading;
}

/** Pasa la captura a texto oscuro sobre blanco (MT5 usa fondo negro y números de colores). */
async function prepare(file) {
  const bmp = await createImageBitmap(file);
  const scale = Math.min(2, Math.max(1, 2000 / bmp.width));
  const w = Math.round(bmp.width * scale), h = Math.round(bmp.height * scale);
  const c = document.createElement('canvas'); c.width = w; c.height = h;
  const g = c.getContext('2d', { willReadFrequently: true });
  g.imageSmoothingQuality = 'high'; g.drawImage(bmp, 0, 0, w, h);
  const im = g.getImageData(0, 0, w, h), p = im.data;
  let sum = 0;
  for (let i = 0; i < p.length; i += 4) sum += (p[i] + p[i + 1] + p[i + 2]) / 3;
  const dark = sum / (p.length / 4) < 128;
  let lo = 255, hi = 0;
  const v = new Uint8ClampedArray(p.length / 4);
  for (let i = 0, j = 0; i < p.length; i += 4, j++) {
    // Fondo oscuro: el texto (blanco, azul o rojo) es el canal más claro → se invierte.
    const x = dark ? 255 - Math.max(p[i], p[i + 1], p[i + 2]) : Math.min(p[i], p[i + 1], p[i + 2]);
    v[j] = x; if (x < lo) lo = x; if (x > hi) hi = x;
  }
  const k = hi > lo ? 255 / (hi - lo) : 1;
  for (let i = 0, j = 0; i < p.length; i += 4, j++) { const x = (v[j] - lo) * k; p[i] = p[i + 1] = p[i + 2] = x; p[i + 3] = 255; }
  g.putImageData(im, 0, 0);
  return c;
}

const NUM = /-?\d{1,3}(?:[ \u00a0]\d{3})+[.,]\d{2}(?!\d)|-?\d+[.,]\d{2}(?!\d)/g;
const toNum = s => parseFloat(s.replace(/[ \u00a0]/g, '').replace(',', '.'));
const lastNum = line => { const m = line.match(NUM); return m ? toNum(m[m.length - 1]) : null; };

/** Interpreta el texto reconocido. Importes en la moneda de la cuenta (USC en tus cuentas). */
export function parseShot(text) {
  const lines = text.split(/\r?\n/).map(l => l.trim()).filter(Boolean);
  const pick = re => { const l = lines.find(x => re.test(x)); return l ? lastNum(l.replace(re, ' ')) : null; };
  const r = {
    profit: pick(/benef[il1]c[il1]o|profit/i),
    deposit: pick(/dep[oó0]s[il1]to|deposit/i),
    swap: pick(/swap/i),
    commission: pick(/com[il1]s[il1][oó0]n|commission/i),
    balance: pick(/balance/i),
    dates: [], trades: [],
  };
  const dateRe = /(20\d\d)[.\-/](\d\d)[.\-/](\d\d)/g;
  for (const l of lines) for (const m of l.matchAll(dateRe)) r.dates.push(`${m[1]}-${m[2]}-${m[3]}`);
  r.dates = [...new Set(r.dates)].sort();
  // Línea de cada posición: "precio apertura → precio cierre   beneficio"
  lines.forEach((l, i) => {
    if (i === 0 || !/(buy|sell)/i.test(lines[i - 1])) return;
    const clean = l.replace(dateRe, ' ').replace(/\d\d:\d\d(:\d\d)?/g, ' ');
    const m = clean.match(NUM); if (m && m.length >= 3) r.trades.push(toNum(m[m.length - 1]));
  });
  return r;
}

/** Captura → resumen. onProgress(0..1) mientras reconoce. */
export async function readShot(file, onProgress) {
  const T = await loadTesseract();
  const canvas = await prepare(file);
  const w = await T.createWorker('eng', 1, {
    logger: m => { if (m.status === 'recognizing text' && onProgress) onProgress(m.progress); },
  });
  let res;
  try {
    await w.setParameters({ tessedit_pageseg_mode: '6' }); // un bloque de texto uniforme: respeta las filas de MT5
    res = await w.recognize(canvas);
  } finally { w.terminate(); }
  const r = parseShot(res.data.text);
  r.text = res.data.text;
  return r;
}
