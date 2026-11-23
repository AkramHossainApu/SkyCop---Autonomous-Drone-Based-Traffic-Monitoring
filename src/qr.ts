// Minimal QR code encoder (byte mode, error correction level M, versions 1-10), so the site needs no
// extra dependency. Returns a square matrix of booleans, true for dark modules.

const EC_PER_BLOCK = [0, 10, 16, 26, 18, 24, 16, 18, 22, 22, 26];
// [blocks in group 1, data codewords each, blocks in group 2, data codewords each] for level M.
const BLOCKS: number[][] = [
  [], [1, 16, 0, 0], [1, 28, 0, 0], [1, 44, 0, 0], [2, 32, 0, 0], [2, 43, 0, 0],
  [4, 27, 0, 0], [4, 31, 0, 0], [2, 38, 2, 39], [3, 36, 2, 37], [4, 43, 1, 44],
];
const ALIGN: number[][] = [
  [], [], [6, 18], [6, 22], [6, 26], [6, 30], [6, 34], [6, 22, 38], [6, 24, 42], [6, 26, 46], [6, 28, 50],
];

const EXP = new Uint8Array(512);
const LOG = new Uint8Array(256);
{
  let x = 1;
  for (let i = 0; i < 255; i++) {
    EXP[i] = x; LOG[x] = i;
    x <<= 1;
    if (x & 256) x ^= 0x11d;
  }
  for (let i = 255; i < 512; i++) EXP[i] = EXP[i - 255];
}

function multiply(a: number, b: number) {
  return a && b ? EXP[LOG[a] + LOG[b]] : 0;
}

function reedSolomon(data: number[], degree: number): number[] {
  let generator = [1];
  for (let i = 0; i < degree; i++) {
    const next = new Array(generator.length + 1).fill(0);
    generator.forEach((coefficient, j) => {
      next[j] ^= coefficient;
      next[j + 1] ^= multiply(coefficient, EXP[i]);
    });
    generator = next;
  }
  const remainder = new Array(degree).fill(0);
  for (const byte of data) {
    const factor = byte ^ remainder.shift()!;
    remainder.push(0);
    for (let i = 0; i < degree; i++) remainder[i] ^= multiply(generator[i + 1], factor);
  }
  return remainder;
}

function capacity(version: number) {
  const [b1, d1, b2, d2] = BLOCKS[version];
  return b1 * d1 + b2 * d2;
}

function bchBits(value: number, poly: number, polyBits: number) {
  let v = value << (polyBits - 1);
  for (let i = 31 - Math.clz32(v); i >= polyBits - 1; i = 31 - Math.clz32(v)) {
    if (v & (1 << i)) v ^= poly << (i - polyBits + 1);
    if (v < 1 << (polyBits - 1)) break;
  }
  return v;
}

export function encodeQr(text: string): boolean[][] {
  const bytes = Array.from(new TextEncoder().encode(text));
  let version = 1;
  while (version <= 10 && capacity(version) * 8 < 4 + (version < 10 ? 8 : 16) + bytes.length * 8) version++;
  if (version > 10) throw new Error('Text is too long for a QR code.');

  // Data bit stream.
  const bits: number[] = [];
  const put = (value: number, length: number) => {
    for (let i = length - 1; i >= 0; i--) bits.push((value >>> i) & 1);
  };
  put(0b0100, 4);
  put(bytes.length, version < 10 ? 8 : 16);
  bytes.forEach((byte) => put(byte, 8));
  const total = capacity(version) * 8;
  put(0, Math.min(4, total - bits.length));
  while (bits.length % 8) bits.push(0);
  const data: number[] = [];
  for (let i = 0; i < bits.length; i += 8) data.push(parseInt(bits.slice(i, i + 8).join(''), 2));
  for (let pad = 0; data.length < capacity(version); pad++) data.push(pad % 2 ? 0x11 : 0xec);

  // Split into blocks, add error correction, interleave.
  const [b1, d1, b2, d2] = BLOCKS[version];
  const blocks: number[][] = [];
  let at = 0;
  for (let i = 0; i < b1 + b2; i++) {
    const length = i < b1 ? d1 : d2;
    blocks.push(data.slice(at, at + length));
    at += length;
  }
  const ec = blocks.map((block) => reedSolomon(block, EC_PER_BLOCK[version]));
  const codewords: number[] = [];
  for (let i = 0; i < Math.max(d1, d2); i++) blocks.forEach((block) => { if (i < block.length) codewords.push(block[i]); });
  for (let i = 0; i < EC_PER_BLOCK[version]; i++) ec.forEach((block) => codewords.push(block[i]));

  const size = version * 4 + 17;
  const modules: boolean[][] = Array.from({ length: size }, () => new Array(size).fill(false));
  const reserved: boolean[][] = Array.from({ length: size }, () => new Array(size).fill(false));
  const set = (row: number, column: number, dark: boolean) => {
    modules[row][column] = dark;
    reserved[row][column] = true;
  };

  const finder = (row: number, column: number) => {
    for (let r = -1; r <= 7; r++) for (let c = -1; c <= 7; c++) {
      const y = row + r; const x = column + c;
      if (y < 0 || y >= size || x < 0 || x >= size) continue;
      const ring = Math.max(Math.abs(r - 3), Math.abs(c - 3));
      set(y, x, ring !== 2 && ring !== 4);
    }
  };
  finder(0, 0); finder(0, size - 7); finder(size - 7, 0);
  for (let i = 8; i < size - 8; i++) {
    set(6, i, i % 2 === 0);
    set(i, 6, i % 2 === 0);
  }
  const centers = ALIGN[version];
  for (const row of centers) for (const column of centers) {
    // Only the three that would land on a finder pattern are left out.
    const nearEdge = (v: number) => v < 9 || v > size - 10;
    if ((row < 9 || row > size - 10) && nearEdge(column) && !(row > size - 10 && column > size - 10)) continue;
    for (let r = -2; r <= 2; r++) for (let c = -2; c <= 2; c++) {
      set(row + r, column + c, Math.max(Math.abs(r), Math.abs(c)) !== 1);
    }
  }
  // Format and version areas are reserved now and written after masking.
  for (let i = 0; i < 9; i++) {
    reserved[8][i] = true; reserved[i][8] = true;
    if (i < 8) { reserved[8][size - 1 - i] = true; reserved[size - 1 - i][8] = true; }
  }
  set(size - 8, 8, true);
  if (version >= 7) {
    for (let i = 0; i < 6; i++) for (let j = 0; j < 3; j++) {
      reserved[i][size - 11 + j] = true; reserved[size - 11 + j][i] = true;
    }
  }

  // Place data in the zigzag order.
  const stream: number[] = [];
  codewords.forEach((codeword) => { for (let i = 7; i >= 0; i--) stream.push((codeword >>> i) & 1); });
  let bit = 0;
  let upward = true;
  for (let column = size - 1; column > 0; column -= 2) {
    if (column === 6) column = 5;
    for (let step = 0; step < size; step++) {
      const row = upward ? size - 1 - step : step;
      for (const x of [column, column - 1]) {
        if (reserved[row][x]) continue;
        modules[row][x] = bit < stream.length ? stream[bit] === 1 : false;
        bit++;
      }
    }
    upward = !upward;
  }

  const masks: ((r: number, c: number) => boolean)[] = [
    (r, c) => (r + c) % 2 === 0,
    (r) => r % 2 === 0,
    (_r, c) => c % 3 === 0,
    (r, c) => (r + c) % 3 === 0,
    (r, c) => (Math.floor(r / 2) + Math.floor(c / 3)) % 2 === 0,
    (r, c) => ((r * c) % 2) + ((r * c) % 3) === 0,
    (r, c) => (((r * c) % 2) + ((r * c) % 3)) % 2 === 0,
    (r, c) => (((r + c) % 2) + ((r * c) % 3)) % 2 === 0,
  ];

  const withMask = (mask: number) => {
    const grid = modules.map((row) => row.slice());
    for (let r = 0; r < size; r++) for (let c = 0; c < size; c++) {
      if (!reserved[r][c] && masks[mask](r, c)) grid[r][c] = !grid[r][c];
    }
    // Level M is 00; the format bits are BCH coded and XOR-ed with a fixed pattern.
    const format = (((0b00 << 3) | mask) << 10 | bchBits((0b00 << 3) | mask, 0x537, 11)) ^ 0x5412;
    for (let i = 0; i < 15; i++) {
      const dark = ((format >>> i) & 1) === 1;
      const a = i < 6 ? i : i < 8 ? i + 1 : size - 15 + i;
      grid[a][8] = dark;
      const b = i < 8 ? size - 1 - i : i < 9 ? 15 - i : 14 - i;
      grid[8][b] = dark;
    }
    grid[size - 8][8] = true;
    if (version >= 7) {
      const info = (version << 12) | bchBits(version, 0x1f25, 13);
      for (let i = 0; i < 18; i++) {
        const dark = ((info >>> i) & 1) === 1;
        const r = Math.floor(i / 3); const c = size - 11 + (i % 3);
        grid[r][c] = dark; grid[c][r] = dark;
      }
    }
    return grid;
  };

  // Pick the mask with the lowest standard penalty.
  let best = withMask(0);
  let bestScore = Infinity;
  for (let mask = 0; mask < 8; mask++) {
    const grid = withMask(mask);
    const score = penalty(grid);
    if (score < bestScore) { bestScore = score; best = grid; }
  }
  return best;
}

function penalty(grid: boolean[][]) {
  const size = grid.length;
  let score = 0;
  for (let pass = 0; pass < 2; pass++) {
    for (let a = 0; a < size; a++) {
      let run = 1;
      for (let b = 1; b < size; b++) {
        const current = pass ? grid[b][a] : grid[a][b];
        const previous = pass ? grid[b - 1][a] : grid[a][b - 1];
        if (current === previous) {
          run++;
          if (b === size - 1 && run >= 5) score += run - 2;
        } else {
          if (run >= 5) score += run - 2;
          run = 1;
        }
      }
    }
  }
  for (let r = 0; r < size - 1; r++) for (let c = 0; c < size - 1; c++) {
    const v = grid[r][c];
    if (v === grid[r][c + 1] && v === grid[r + 1][c] && v === grid[r + 1][c + 1]) score += 3;
  }
  const pattern = [true, false, true, true, true, false, true, false, false, false, false];
  const reversed = pattern.slice().reverse();
  for (let r = 0; r < size; r++) for (let c = 0; c <= size - 11; c++) {
    for (const p of [pattern, reversed]) {
      if (p.every((v, i) => grid[r][c + i] === v)) score += 40;
      if (p.every((v, i) => grid[c + i][r] === v)) score += 40;
    }
  }
  let dark = 0;
  grid.forEach((row) => row.forEach((v) => { if (v) dark++; }));
  score += Math.floor(Math.abs((dark * 100) / (size * size) - 50) / 5) * 10;
  return score;
}
