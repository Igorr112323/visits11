(function (global) {
  "use strict";

  function matrix(bytes) {
    var CAPACITY = { 1: 19, 2: 34, 3: 55, 4: 80 };
    var EC = { 1: 7, 2: 10, 3: 15, 4: 20 };
    var version = 0;
    for (var v = 1; v <= 4; v++) {
      if (bytes.length + 2 <= CAPACITY[v]) { version = v; break; }
    }
    if (!version) return null;

    var dataCodewords = CAPACITY[version];
    var ecCodewords = EC[version];
    var size = 17 + 4 * version;

    var bits = [];
    function push(value, length) {
      for (var i = length - 1; i >= 0; i--) bits.push((value >> i) & 1);
    }
    push(0b0100, 4);
    push(bytes.length, 8);
    for (var b = 0; b < bytes.length; b++) push(bytes[b], 8);
    var capacityBits = dataCodewords * 8;
    for (var t = 0; t < 4 && bits.length < capacityBits; t++) bits.push(0);
    while (bits.length % 8 !== 0) bits.push(0);

    var data = [];
    for (var i2 = 0; i2 < bits.length; i2 += 8) {
      var byte = 0;
      for (var j = 0; j < 8; j++) byte = (byte << 1) | bits[i2 + j];
      data.push(byte);
    }
    for (var p = 0; data.length < dataCodewords; p++) data.push(p % 2 ? 0x11 : 0xec);

    var EXP = new Array(512), LOG = new Array(256), x = 1;
    for (var e = 0; e < 255; e++) {
      EXP[e] = x; LOG[x] = e; x <<= 1; if (x & 0x100) x ^= 0x11d;
    }
    for (var e2 = 255; e2 < 512; e2++) EXP[e2] = EXP[e2 - 255];
    function mul(a, b) { return (a === 0 || b === 0) ? 0 : EXP[LOG[a] + LOG[b]]; }

    var generator = [1];
    for (var g = 0; g < ecCodewords; g++) {
      var next = new Array(generator.length + 1).fill(0);
      for (var k = 0; k < generator.length; k++) {
        next[k] ^= generator[k];
        next[k + 1] ^= mul(generator[k], EXP[g]);
      }
      generator = next;
    }
    var remainder = new Array(ecCodewords).fill(0);
    for (var d = 0; d < data.length; d++) {
      var factor = data[d] ^ remainder[0];
      remainder.shift();
      remainder.push(0);
      for (var r = 0; r < ecCodewords; r++) remainder[r] ^= mul(generator[r + 1], factor);
    }
    var codewords = data.concat(remainder);

    var matrix = [], reserved = [];
    for (var i3 = 0; i3 < size; i3++) {
      matrix.push(new Array(size).fill(0));
      reserved.push(new Array(size).fill(false));
    }
    function setFinder(row, col) {
      for (var r2 = -1; r2 <= 7; r2++) {
        for (var c2 = -1; c2 <= 7; c2++) {
          var rr = row + r2, cc = col + c2;
          if (rr < 0 || cc < 0 || rr >= size || cc >= size) continue;
          var inside = r2 >= 0 && r2 <= 6 && c2 >= 0 && c2 <= 6;
          var dark = inside && (r2 === 0 || r2 === 6 || c2 === 0 || c2 === 6 ||
            (r2 >= 2 && r2 <= 4 && c2 >= 2 && c2 <= 4));
          matrix[rr][cc] = dark ? 1 : 0;
          reserved[rr][cc] = true;
        }
      }
    }
    setFinder(0, 0); setFinder(0, size - 7); setFinder(size - 7, 0);

    for (var i4 = 8; i4 < size - 8; i4++) {
      var value = i4 % 2 === 0 ? 1 : 0;
      matrix[6][i4] = value; reserved[6][i4] = true;
      matrix[i4][6] = value; reserved[i4][6] = true;
    }

    if (version >= 2) {
      var center = size - 7;
      for (var r3 = center - 2; r3 <= center + 2; r3++) {
        for (var c3 = center - 2; c3 <= center + 2; c3++) {
          var edge = r3 === center - 2 || r3 === center + 2 || c3 === center - 2 || c3 === center + 2;
          matrix[r3][c3] = (edge || (r3 === center && c3 === center)) ? 1 : 0;
          reserved[r3][c3] = true;
        }
      }
    }

    for (var i5 = 0; i5 < 9; i5++) {
      if (i5 === 6) continue;
      if (!reserved[8][i5]) { reserved[8][i5] = true; }
      if (!reserved[i5][8]) { reserved[i5][8] = true; }
    }
    for (var i6 = size - 8; i6 < size; i6++) {
      if (!reserved[8][i6]) reserved[8][i6] = true;
      if (!reserved[i6][8]) reserved[i6][8] = true;
    }
    matrix[size - 8][8] = 1; reserved[size - 8][8] = true;

    var stream = [];
    for (var w = 0; w < codewords.length; w++) {
      for (var bb = 7; bb >= 0; bb--) stream.push((codewords[w] >> bb) & 1);
    }
    var index = 0, upward = true;
    for (var col = size - 1; col > 0; col -= 2) {
      if (col === 6) col--;
      for (var step = 0; step < size; step++) {
        var row = upward ? size - 1 - step : step;
        for (var pair = 0; pair < 2; pair++) {
          var c = col - pair;
          if (reserved[row][c]) continue;
          matrix[row][c] = index < stream.length ? stream[index++] : 0;
        }
      }
      upward = !upward;
    }

    var maskFn = [
      function (r, c) { return (r + c) % 2 === 0; },
      function (r) { return r % 2 === 0; },
      function (r, c) { return c % 3 === 0; },
      function (r, c) { return (r + c) % 3 === 0; },
      function (r, c) { return (Math.floor(r / 2) + Math.floor(c / 3)) % 2 === 0; },
      function (r, c) { return ((r * c) % 2) + ((r * c) % 3) === 0; },
      function (r, c) { return (((r * c) % 2) + ((r * c) % 3)) % 2 === 0; },
      function (r, c) { return (((r + c) % 2) + ((r * c) % 3)) % 2 === 0; }
    ];

    function applyMask(source, mask) {
      var out = source.map(function (row) { return row.slice(); });
      for (var r = 0; r < size; r++) {
        for (var c = 0; c < size; c++) {
          if (!reserved[r][c] && maskFn[mask](r, c)) out[r][c] ^= 1;
        }
      }
      return out;
    }

    function penalty(grid) {
      var score = 0, r, c, run;
      for (r = 0; r < size; r++) {
        run = 1;
        for (c = 1; c < size; c++) {
          if (grid[r][c] === grid[r][c - 1]) run++;
          else { if (run >= 5) score += 3 + (run - 5); run = 1; }
        }
        if (run >= 5) score += 3 + (run - 5);
      }
      for (c = 0; c < size; c++) {
        run = 1;
        for (r = 1; r < size; r++) {
          if (grid[r][c] === grid[r - 1][c]) run++;
          else { if (run >= 5) score += 3 + (run - 5); run = 1; }
        }
        if (run >= 5) score += 3 + (run - 5);
      }
      for (r = 0; r < size - 1; r++) {
        for (c = 0; c < size - 1; c++) {
          var v = grid[r][c];
          if (v === grid[r][c + 1] && v === grid[r + 1][c] && v === grid[r + 1][c + 1]) score += 3;
        }
      }
      var pattern = [1, 0, 1, 1, 1, 0, 1, 0, 0, 0, 0];
      var reversed = pattern.slice().reverse();
      function hasAt(get, start, target) {
        for (var i = 0; i < target.length; i++) {
          if (get(start + i) !== target[i]) return false;
        }
        return true;
      }
      for (r = 0; r < size; r++) {
        for (c = 0; c + pattern.length <= size; c++) {
          if (hasAt(function (i) { return grid[r][i]; }, c, pattern)) score += 40;
          if (hasAt(function (i) { return grid[r][i]; }, c, reversed)) score += 40;
        }
      }
      for (c = 0; c < size; c++) {
        for (r = 0; r + pattern.length <= size; r++) {
          if (hasAt(function (i) { return grid[i][c]; }, r, pattern)) score += 40;
          if (hasAt(function (i) { return grid[i][c]; }, r, reversed)) score += 40;
        }
      }
      var dark = 0;
      for (r = 0; r < size; r++) {
        for (c = 0; c < size; c++) dark += grid[r][c];
      }
      score += Math.floor(Math.abs((dark * 100) / (size * size) - 50) / 5) * 10;
      return score;
    }

    var best = null, bestScore = Infinity;
    for (var mask = 0; mask < 8; mask++) {
      var candidate = applyMask(matrix, mask);
      var score = penalty(candidate);
      if (score < bestScore) { bestScore = score; best = { mask: mask, grid: candidate }; }
    }

    var formatData = (0b01 << 3) | best.mask;
    var rem = formatData;
    for (var i7 = 0; i7 < 10; i7++) rem = (rem << 1) ^ ((rem >>> 9) * 0x537);
    var formatBits = ((formatData << 10) | rem) ^ 0x5412;
    var fmt = [];
    for (var k2 = 0; k2 < 15; k2++) fmt.push((formatBits >>> (14 - k2)) & 1);

    var grid = best.grid;
    for (var k3 = 0; k3 < 6; k3++) grid[8][k3] = fmt[k3];
    grid[8][7] = fmt[6];
    grid[8][8] = fmt[7];
    grid[7][8] = fmt[8];
    for (var k4 = 9; k4 < 15; k4++) grid[14 - k4][8] = fmt[k4];
    for (var k5 = 0; k5 < 8; k5++) grid[size - 1 - k5][8] = fmt[k5];
    for (var k6 = 8; k6 < 15; k6++) grid[8][size - 15 + k6] = fmt[k6];
    grid[size - 8][8] = 1;

    return grid;
  }

  var PRIMES = [];
  for (var candidate = 2; PRIMES.length < 64; candidate++) {
    var prime = true;
    for (var q = 0; q < PRIMES.length; q++) {
      if (candidate % PRIMES[q] === 0) { prime = false; break; }
    }
    if (prime) PRIMES.push(candidate);
  }
  var K = PRIMES.map(function (p) { return (Math.pow(p, 1 / 3) % 1 * 4294967296) | 0; });
  var H0 = PRIMES.slice(0, 8).map(function (p) { return (Math.pow(p, 0.5) % 1 * 4294967296) | 0; });

  function rotr(x, n) { return (x >>> n) | (x << (32 - n)); }

  function sha256(bytes) {
    var h = H0.slice();
    var length = bytes.length;
    var total = ((length + 9 + 63) >> 6) << 6;
    var buffer = new Uint8Array(total);
    buffer.set(bytes);
    buffer[length] = 0x80;
    var view = new DataView(buffer.buffer);
    view.setUint32(total - 8, Math.floor(length / 0x20000000));
    view.setUint32(total - 4, (length << 3) >>> 0);
    var w = new Array(64);
    for (var offset = 0; offset < total; offset += 64) {
      for (var i = 0; i < 16; i++) w[i] = view.getUint32(offset + i * 4);
      for (i = 16; i < 64; i++) {
        var s0 = rotr(w[i - 15], 7) ^ rotr(w[i - 15], 18) ^ (w[i - 15] >>> 3);
        var s1 = rotr(w[i - 2], 17) ^ rotr(w[i - 2], 19) ^ (w[i - 2] >>> 10);
        w[i] = (w[i - 16] + s0 + w[i - 7] + s1) | 0;
      }
      var a = h[0], b = h[1], c = h[2], d = h[3], e = h[4], f = h[5], g = h[6], hh = h[7];
      for (i = 0; i < 64; i++) {
        var t1 = (hh + (rotr(e, 6) ^ rotr(e, 11) ^ rotr(e, 25)) + ((e & f) ^ (~e & g)) + K[i] + w[i]) | 0;
        var t2 = ((rotr(a, 2) ^ rotr(a, 13) ^ rotr(a, 22)) + ((a & b) ^ (a & c) ^ (b & c))) | 0;
        hh = g; g = f; f = e; e = (d + t1) | 0; d = c; c = b; b = a; a = (t1 + t2) | 0;
      }
      h[0] = (h[0] + a) | 0; h[1] = (h[1] + b) | 0; h[2] = (h[2] + c) | 0; h[3] = (h[3] + d) | 0;
      h[4] = (h[4] + e) | 0; h[5] = (h[5] + f) | 0; h[6] = (h[6] + g) | 0; h[7] = (h[7] + hh) | 0;
    }
    var out = new Uint8Array(32);
    var outView = new DataView(out.buffer);
    for (i = 0; i < 8; i++) outView.setUint32(i * 4, h[i] >>> 0);
    return out;
  }

  function hmac(key, message) {
    var block = new Uint8Array(64);
    block.set(key.length > 64 ? sha256(key) : key);
    var inner = new Uint8Array(64 + message.length);
    var outer = new Uint8Array(96);
    for (var i = 0; i < 64; i++) {
      inner[i] = block[i] ^ 0x36;
      outer[i] = block[i] ^ 0x5c;
    }
    inner.set(message, 64);
    outer.set(sha256(inner), 64);
    return sha256(outer);
  }

  function hexToBytes(hex) {
    var out = new Uint8Array(hex.length >> 1);
    for (var i = 0; i < out.length; i++) out[i] = parseInt(hex.substr(i * 2, 2), 16);
    return out;
  }

  var WINDOW_MS = 3000;

  function windowOf(now) {
    return Math.floor(now / WINDOW_MS);
  }

  var B64 = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789-_";

  function base64Url(bytes) {
    var out = "";
    for (var i = 0; i < bytes.length; i += 3) {
      var n = (bytes[i] << 16) | ((bytes[i + 1] || 0) << 8) | (bytes[i + 2] || 0);
      out += B64.charAt((n >> 18) & 63) + B64.charAt((n >> 12) & 63);
      if (i + 1 < bytes.length) out += B64.charAt((n >> 6) & 63);
      if (i + 2 < bytes.length) out += B64.charAt(n & 63);
    }
    return out;
  }

  function payload(studentId, keyHex, now) {
    var head = new Uint8Array(12);
    head.set([0x56, 0x31, 0x31, 0x42]);
    var view = new DataView(head.buffer);
    view.setUint32(4, studentId, true);
    view.setUint32(8, windowOf(now) >>> 0, true);
    var body = new Uint8Array(20);
    body.set(head.subarray(4));
    body.set(hmac(hexToBytes(keyHex), head).subarray(0, 12), 8);
    var text = "V11B" + base64Url(body);
    var out = new Uint8Array(text.length);
    for (var i = 0; i < text.length; i++) out[i] = text.charCodeAt(i);
    return out;
  }

  function svgUrl(grid) {
    var quiet = 4;
    var size = grid.length + quiet * 2;
    var path = "";
    for (var r = 0; r < grid.length; r++) {
      for (var c = 0; c < grid.length; c++) {
        if (grid[r][c]) path += "M" + (c + quiet) + " " + (r + quiet) + "h1v1h-1z";
      }
    }
    var svg = '<svg xmlns="http://www.w3.org/2000/svg" viewBox="0 0 ' + size + " " + size +
      '" shape-rendering="crispEdges"><rect width="' + size + '" height="' + size +
      '" fill="#fff"/><path d="' + path + '" fill="#000"/></svg>';
    return "data:image/svg+xml;charset=utf-8," + encodeURIComponent(svg);
  }

  global.V11 = {
    matrix: matrix,
    svgUrl: svgUrl,
    sha256: sha256,
    hmac: hmac,
    hexToBytes: hexToBytes,
    windowOf: windowOf,
    payload: payload
  };
})(window);
