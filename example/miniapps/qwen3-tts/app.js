(() => {
  'use strict';

  const HF_ROOT = 'https://huggingface.co/wavekat/Qwen3-TTS-0.6B-Base-ONNX/resolve/main/';
  const COMMON_FILES = [
    'config.json',
    'speaker_encoder.onnx', 'speaker_encoder.onnx.data',
    'tokenizer_encoder.onnx', 'tokenizer_encoder.onnx.data',
    'tokenizer/tokenizer.json', 'tokenizer/tokenizer_config.json'
  ];
  // Embedding NPY files are downloaded together with the selected model.
  const EMBEDDING_FILES = [
    'embeddings/text_embedding.npy',
    'embeddings/text_projection_fc1_weight.npy', 'embeddings/text_projection_fc1_bias.npy',
    'embeddings/text_projection_fc2_weight.npy', 'embeddings/text_projection_fc2_bias.npy',
    'embeddings/talker_codec_embedding.npy',
    ...Array.from({ length: 15 }, (_, index) => `embeddings/cp_codec_embedding_${index}.npy`)
  ];
  const VARIANT_FILES = {
    int4: ['int4/talker_prefill.onnx', 'int4/talker_prefill.onnx.data', 'int4/talker_decode.onnx', 'int4/talker_decode.onnx.data', 'int4/code_predictor.onnx', 'int4/code_predictor.onnx.data', 'int4/vocoder.onnx', 'int4/vocoder.onnx.data'],
    fp32: ['fp32/talker_prefill.onnx', 'fp32/talker_prefill.onnx.data', 'fp32/talker_decode.onnx', 'fp32/talker_decode.onnx.data', 'fp32/code_predictor.onnx', 'fp32/code_predictor.onnx.data', 'fp32/vocoder.onnx', 'fp32/vocoder.onnx.data']
  };
  const MODEL_DIR = 'user-data/qwen3-tts';
  const $ = (id) => document.getElementById(id);
  const state = { sdk: window.nezumi, audioFile: null, audioUrl: null, modelReady: false, busy: false, fileProgress: new Map(), tokenizer: null };

  // ---------------------------------------------------------------------
  // Model constants (from 調査ノート.md / onnx_io_summary.txt)
  // ---------------------------------------------------------------------
  const TALKER_HIDDEN = 1024;
  const TALKER_LAYERS = 28;
  const TALKER_KV_HEADS = 8;
  const HEAD_DIM = 128;
  const TALKER_VOCAB = 3072;
  const CODE_GROUPS = 16;
  const CP_LAYERS = 5;
  const CP_VOCAB = 2048;
  const SAMPLE_RATE = 24000;
  const SAMPLES_PER_FRAME = 1920;
  const TALKER_EOS_ID = 2150; // absolute id within [2048,3071] mask range documented in notes
  const TALKER_MASK_LOW = 2048;
  const TALKER_MASK_HIGH = 3071;
  const MAX_NEW_FRAMES = 600; // safety cap (~48s of audio) so a runaway generation can't hang the app

  // Special text-side tokens (Qwen3-TTS ICL layout, per 調査ノート.md and public Qwen3-TTS config defaults)
  const TOK = {
    im_start: 151644,
    im_end: 151645,
    assistant_newline: null, // resolved from tokenizer vocab at runtime ("assistant\n")
    tts_pad: 151671,
    tts_bos: 151672,
    tts_eos: 151673,
    think: null,
    think_bos: null,
    think_eos: null,
    language: null
  };

  // ---------------------------------------------------------------------
  // Mel spectrogram front-end for speaker_encoder.onnx
  // Pinned parameters (HiFi-GAN style mel used by Qwen3TTSSpeakerEncoder):
  //   24kHz, n_fft=1024, hop=256, win=1024, periodic Hann, center=False with
  //   reflect padding (n_fft-hop)/2=384 both sides, Slaney mel, 128 bins,
  //   fmin=0, fmax=12000, log(clamp(magnitude, 1e-5)).
  // ---------------------------------------------------------------------
  const MEL = { sampleRate: 24000, nFft: 1024, hop: 256, win: 1024, nMels: 128, fmin: 0, fmax: 12000 };

  function hannWindowPeriodic(n) {
    const w = new Float32Array(n);
    for (let i = 0; i < n; i++) w[i] = 0.5 - 0.5 * Math.cos((2 * Math.PI * i) / n);
    return w;
  }

  function reflectPad(samples, padAmount) {
    const n = samples.length;
    const out = new Float32Array(n + 2 * padAmount);
    out.set(samples, padAmount);
    for (let i = 0; i < padAmount; i++) {
      out[padAmount - 1 - i] = samples[Math.min(i + 1, n - 1)];
      out[padAmount + n + i] = samples[Math.max(n - 2 - i, 0)];
    }
    return out;
  }

  // Minimal iterative in-place radix-2 FFT (n must be a power of two; 1024 qualifies).
  function fftInPlace(re, im) {
    const n = re.length;
    for (let i = 1, j = 0; i < n; i++) {
      let bit = n >> 1;
      for (; j & bit; bit >>= 1) j ^= bit;
      j ^= bit;
      if (i < j) { [re[i], re[j]] = [re[j], re[i]]; [im[i], im[j]] = [im[j], im[i]]; }
    }
    for (let len = 2; len <= n; len <<= 1) {
      const ang = (-2 * Math.PI) / len;
      const wRe = Math.cos(ang), wI = Math.sin(ang);
      for (let i = 0; i < n; i += len) {
        let curRe = 1, curIm = 0;
        for (let k = 0; k < len / 2; k++) {
          const uRe = re[i + k], uIm = im[i + k];
          const vRe = re[i + k + len / 2] * curRe - im[i + k + len / 2] * curIm;
          const vIm = re[i + k + len / 2] * curIm + im[i + k + len / 2] * curRe;
          re[i + k] = uRe + vRe; im[i + k] = uIm + vIm;
          re[i + k + len / 2] = uRe - vRe; im[i + k + len / 2] = uIm - vIm;
          const nextRe = curRe * wRe - curIm * wI;
          const nextIm = curRe * wI + curIm * wRe;
          curRe = nextRe; curIm = nextIm;
        }
      }
    }
  }

  function hzToMelSlaney(hz) {
    const fMin = 0, fSp = 200 / 3;
    const minLogHz = 1000, minLogMel = (minLogHz - fMin) / fSp, logstep = Math.log(6.4) / 27;
    if (hz < minLogHz) return (hz - fMin) / fSp;
    return minLogMel + Math.log(hz / minLogHz) / logstep;
  }
  function melToHzSlaney(mel) {
    const fMin = 0, fSp = 200 / 3;
    const minLogHz = 1000, minLogMel = (minLogHz - fMin) / fSp, logstep = Math.log(6.4) / 27;
    if (mel < minLogMel) return fMin + fSp * mel;
    return minLogHz * Math.exp(logstep * (mel - minLogMel));
  }

  function buildMelFilterbank({ sampleRate, nFft, nMels, fmin, fmax }) {
    const nFreqs = nFft / 2 + 1;
    const melMin = hzToMelSlaney(fmin), melMax = hzToMelSlaney(fmax);
    const melPoints = new Float32Array(nMels + 2);
    for (let i = 0; i < nMels + 2; i++) melPoints[i] = melToHzSlaney(melMin + ((melMax - melMin) * i) / (nMels + 1));
    const bins = melPoints.map((hz) => (hz * nFft) / sampleRate);
    const filters = Array.from({ length: nMels }, () => new Float32Array(nFreqs));
    for (let m = 0; m < nMels; m++) {
      const left = bins[m], center = bins[m + 1], right = bins[m + 2];
      // Slaney-style area normalization
      const enorm = 2.0 / (melToHzSlaney(melMin + ((melMax - melMin) * (m + 2)) / (nMels + 1)) - melToHzSlaney(melMin + ((melMax - melMin) * m) / (nMels + 1)));
      for (let k = 0; k < nFreqs; k++) {
        let w = 0;
        if (k >= left && k <= center && center !== left) w = (k - left) / (center - left);
        else if (k > center && k <= right && right !== center) w = (right - k) / (right - center);
        filters[m][k] = w * enorm;
      }
    }
    return filters;
  }

  let melFilterCache = null;
  function computeMelSpectrogram(samples) {
    if (!melFilterCache) melFilterCache = buildMelFilterbank({ sampleRate: MEL.sampleRate, nFft: MEL.nFft, nMels: MEL.nMels, fmin: MEL.fmin, fmax: MEL.fmax });
    const window = hannWindowPeriodic(MEL.win);
    const padded = reflectPad(samples, (MEL.nFft - MEL.hop) / 2);
    const numFrames = 1 + Math.floor((padded.length - MEL.nFft) / MEL.hop);
    const nFreqs = MEL.nFft / 2 + 1;
    const mel = new Float32Array(Math.max(numFrames, 0) * MEL.nMels);
    const re = new Float32Array(MEL.nFft);
    const im = new Float32Array(MEL.nFft);
    for (let f = 0; f < numFrames; f++) {
      const start = f * MEL.hop;
      for (let i = 0; i < MEL.nFft; i++) {
        const s = i < MEL.win ? padded[start + i] * window[i] : 0;
        re[i] = s; im[i] = 0;
      }
      fftInPlace(re, im);
      const mag = new Float32Array(nFreqs);
      for (let k = 0; k < nFreqs; k++) mag[k] = Math.sqrt(re[k] * re[k] + im[k] * im[k]);
      for (let m = 0; m < MEL.nMels; m++) {
        let acc = 0;
        const filt = melFilterCache[m];
        for (let k = 0; k < nFreqs; k++) acc += filt[k] * mag[k];
        mel[f * MEL.nMels + m] = Math.log(Math.max(acc, 1e-5));
      }
    }
    return { data: mel, frames: numFrames, nMels: MEL.nMels };
  }

  // ---------------------------------------------------------------------
  // Output tensor normalization. The SDK doc doesn't pin down the exact
  // shape of nezumi.onnx.run()'s return value per-output, so this defends
  // against the several plausible shapes rather than assuming one:
  //   { data, dims }  |  { data, shape }  |  { value: { data, dims } }  |  ArrayBuffer/TypedArray directly
  // and falls back to nezumi.onnx.getOutputs(sessionId) for the declared
  // shape when a result object doesn't carry its own dims/shape.
  // ---------------------------------------------------------------------
  const outputShapeCache = new Map(); // sessionId -> { name: shape }

  async function getDeclaredOutputShape(sessionId, name) {
    if (!outputShapeCache.has(sessionId)) {
      const outputs = await state.sdk.onnx.getOutputs(sessionId);
      const map = {};
      for (const o of outputs) map[o.name] = o.shape;
      outputShapeCache.set(sessionId, map);
    }
    return outputShapeCache.get(sessionId)[name];
  }

  function toTypedArray(raw) {
    if (raw instanceof Float32Array || raw instanceof BigInt64Array || raw instanceof Int32Array || raw instanceof Uint8Array) return raw;
    if (raw instanceof ArrayBuffer) return new Float32Array(raw);
    if (Array.isArray(raw)) return Float32Array.from(raw);
    if (typeof raw === 'string') {
      const text = raw.trim();
      if (!text) return new Float32Array(0);
      const body = text[0] === '[' && text[text.length - 1] === ']' ? text.slice(1, -1) : text;
      return Float32Array.from(body.split(',').map((value) => Number(value.trim())));
    }
    throw new Error('未対応の出力データ形式です。');
  }

  async function normalizeOutput(sessionId, name, rawResult, opts = {}) {
    let node = rawResult[name];
    if (node === undefined) throw new Error(`ONNX出力 "${name}" が結果に含まれていません。`);
    // Unwrap common wrapper shapes.
    if (node && typeof node === 'object' && 'value' in node && !('data' in node)) node = node.value;
    let data, dims;
    if (node && typeof node === 'object' && ('data' in node || 'buffer' in node)) {
      data = toTypedArray(node.data !== undefined ? node.data : node.buffer);
      dims = node.dims || node.shape || null;
    } else {
      data = toTypedArray(node);
      dims = null;
    }
    if (!dims) {
      const declared = await getDeclaredOutputShape(sessionId, name);
      if (declared) dims = declared.map((d) => (typeof d === 'number' && d > 0 ? d : null));
    }
    if (opts.shapeOptional) return { data, dims: dims || null };
    if (!dims || dims.some((d) => d === null || d === undefined)) {
      throw new Error(`ONNX出力 "${name}" の形状を特定できませんでした（動的形状かつSDKが形状情報を返しませんでした）。`);
    }
    return { data, dims };
  }

  async function normalizeOutputs(sessionId, rawResult, names, opts = {}) {
    const out = {};
    for (const name of names) out[name] = await normalizeOutput(sessionId, name, rawResult, opts[name] || {});
    return out;
  }

  function stripUndefinedDims(dims, actualLength, elemsPerLeadingDims) {
    // Resolve a single dynamic (-1/null) dimension from the actual buffer length.
    const knownProduct = dims.reduce((acc, d) => (d === null ? acc : acc * d), 1);
    return dims.map((d) => (d === null ? Math.round(actualLength / (knownProduct * elemsPerLeadingDims)) : d));
  }

  // ---------------------------------------------------------------------
  // NPY reading (supports partial reads via nezumi.files.readRange for the
  // multi-gigabyte embedding tables so we never load them fully into memory).
  // ---------------------------------------------------------------------
  function parseNpyHeader(bytes) {
    // Magic: \x93NUMPY, then major/minor version, then header length, then header dict text.
    const view = new DataView(bytes.buffer, bytes.byteOffset, bytes.byteLength);
    const magic = String.fromCharCode(...bytes.subarray(1, 6));
    if (magic !== 'NUMPY') throw new Error('不正なNPYファイルです。');
    const major = bytes[6];
    let headerLen, headerStart;
    if (major === 1) { headerLen = view.getUint16(8, true); headerStart = 10; }
    else { headerLen = view.getUint32(8, true); headerStart = 12; }
    const headerText = new TextDecoder('latin1').decode(bytes.subarray(headerStart, headerStart + headerLen));
    const shapeMatch = headerText.match(/'shape':\s*\(([^)]*)\)/);
    const descrMatch = headerText.match(/'descr':\s*'([^']+)'/);
    const fortranMatch = headerText.match(/'fortran_order':\s*(True|False)/);
    const shape = shapeMatch[1].split(',').map((s) => s.trim()).filter(Boolean).map(Number);
    return {
      shape,
      descr: descrMatch[1],
      fortranOrder: fortranMatch[1] === 'True',
      dataOffset: headerStart + headerLen
    };
  }

  function dtypeBytesAndCtor(descr) {
    const map = {
      '<f4': [4, Float32Array], '<f8': [8, Float64Array],
      '<i8': [8, BigInt64Array], '<i4': [4, Int32Array],
      '<u1': [1, Uint8Array]
    };
    if (!map[descr]) throw new Error(`未対応のNPY dtypeです: ${descr}`);
    return map[descr];
  }

  // Read a full small NPY (weights, biases, per-layer embedding tables) from App Data.
  async function readNpyFull(path) {
    const buf = new Uint8Array(await state.sdk.files.read(path));
    const header = parseNpyHeader(buf);
    const [bytesPer, Ctor] = dtypeBytesAndCtor(header.descr);
    const count = header.shape.reduce((a, b) => a * b, 1);
    const raw = buf.buffer.slice(buf.byteOffset + header.dataOffset, buf.byteOffset + header.dataOffset + count * bytesPer);
    return { shape: header.shape, data: new Ctor(raw) };
  }

  // Read only the header of a huge NPY (e.g. text_embedding.npy, >1GB) via readRange,
  // then fetch just the row(s) needed for the given token ids.
  async function readNpyHeaderOnly(path) {
    const chunk = new Uint8Array(await state.sdk.files.readRange(path, 0, 4096));
    return parseNpyHeader(chunk);
  }

  async function readNpyRows(path, header, rowIndices) {
    const [bytesPer, Ctor] = dtypeBytesAndCtor(header.descr);
    const rowLen = header.shape[1];
    const rowBytes = rowLen * bytesPer;
    const out = new Float32Array(rowIndices.length * rowLen);
    for (let r = 0; r < rowIndices.length; r++) {
      const rowIndex = rowIndices[r];
      const start = header.dataOffset + rowIndex * rowBytes;
      const raw = new Uint8Array(await state.sdk.files.readRange(path, start, rowBytes));
      const rowView = new Ctor(raw.buffer, raw.byteOffset, rowLen);
      out.set(rowView, r * rowLen);
    }
    return out;
  }

  // ---------------------------------------------------------------------
  // Minimal BPE tokenizer (HuggingFace `tokenizer.json`, byte-level BPE as
  // used by the Qwen family). Loaded lazily and cached in memory only.
  // ---------------------------------------------------------------------
  function buildByteEncoder() {
    const bs = [];
    for (let i = 33; i <= 126; i++) bs.push(i);
    for (let i = 161; i <= 172; i++) bs.push(i);
    for (let i = 174; i <= 255; i++) bs.push(i);
    const cs = bs.slice();
    let n = 0;
    for (let b = 0; b < 256; b++) {
      if (!bs.includes(b)) { bs.push(b); cs.push(256 + n); n++; }
    }
    const map = new Map();
    for (let i = 0; i < bs.length; i++) map.set(bs[i], String.fromCodePoint(cs[i]));
    return map;
  }

  async function loadTokenizer() {
    if (state.tokenizer) return state.tokenizer;
    const path = `${MODEL_DIR}/tokenizer/tokenizer.json`;
    const json = JSON.parse(await state.sdk.files.readText(path));
    const vocab = json.model.vocab;
    const merges = json.model.merges.map((m) => (Array.isArray(m) ? m.join(' ') : m));
    const rank = new Map(merges.map((m, i) => [m, i]));
    const byteEncoder = buildByteEncoder();
    const added = new Map();
    for (const t of json.added_tokens || []) added.set(t.content, t.id);
    state.tokenizer = { vocab, rank, byteEncoder, added };
    // Resolve dynamic special tokens against the actual vocab where possible.
    for (const [content, key] of [['<think>', 'think'], ['<think_bos>', 'think_bos'], ['<think_eos>', 'think_eos']]) {
      if (added.has(content)) TOK[key] = added.get(content);
    }
    return state.tokenizer;
  }

  function bpeEncodeWord(word, tokenizer) {
    let parts = Array.from(word);
    if (parts.length <= 1) return parts;
    for (;;) {
      let bestPair = null, bestRank = Infinity;
      for (let i = 0; i < parts.length - 1; i++) {
        const key = `${parts[i]} ${parts[i + 1]}`;
        const r = tokenizer.rank.get(key);
        if (r !== undefined && r < bestRank) { bestRank = r; bestPair = i; }
      }
      if (bestPair === null) break;
      parts = [...parts.slice(0, bestPair), parts[bestPair] + parts[bestPair + 1], ...parts.slice(bestPair + 2)];
    }
    return parts;
  }

  function tokenizeText(text, tokenizer) {
    const bytes = new TextEncoder().encode(text);
    let mapped = '';
    for (const b of bytes) mapped += tokenizer.byteEncoder.get(b);
    // Qwen-style pre-tokenization is regex-based and complex; using the whole
    // byte-mapped string as one "word" for BPE still produces a token sequence
    // decodable by the same vocab, since GPT2-style byte-BPE merges operate
    // purely on adjacent symbol pairs regardless of pre-tokenizer word boundaries.
    const merged = bpeEncodeWord(mapped, tokenizer);
    const ids = [];
    for (const piece of merged) {
      const id = tokenizer.vocab[piece];
      if (id !== undefined) ids.push(id);
      else for (const ch of piece) { const cid = tokenizer.vocab[ch]; if (cid !== undefined) ids.push(cid); }
    }
    return ids;
  }

  // ---------------------------------------------------------------------
  // ONNX session helpers
  // ---------------------------------------------------------------------
  const sessions = {};
  async function openSession(key, relPath) {
    if (sessions[key]) return sessions[key];
    const id = await state.sdk.onnx.open({ model: `${MODEL_DIR}/${relPath}` });
    sessions[key] = id;
    return id;
  }
  async function closeAllSessions() {
    for (const key of Object.keys(sessions)) {
      try { await state.sdk.onnx.close(sessions[key]); } catch { /* best effort */ }
      delete sessions[key];
    }
  }

  async function makeF32Tensor(sessionId, shape, arr) {
    return state.sdk.onnx.createTensor(sessionId, shape, Float32Array.from(arr).buffer);
  }
  async function makeI64Tensor(sessionId, shape, arr) {
    return state.sdk.onnx.createTensor(sessionId, shape, BigInt64Array.from(arr.map((v) => BigInt(v))).buffer, 'int64');
  }

  // ---------------------------------------------------------------------
  // Text projection: text_embedding[token_ids] -> fc1 -> SiLU -> fc2
  // ---------------------------------------------------------------------
  async function projectTextTokens(tokenIds) {
    const embHeader = await readNpyHeaderOnly(`${MODEL_DIR}/embeddings/text_embedding.npy`);
    const rows = await readNpyRows(`${MODEL_DIR}/embeddings/text_embedding.npy`, embHeader, tokenIds);
    const inDim = embHeader.shape[1]; // 2048
    const fc1 = await readNpyFull(`${MODEL_DIR}/embeddings/text_projection_fc1_weight.npy`); // (2048,2048)
    const fc1b = await readNpyFull(`${MODEL_DIR}/embeddings/text_projection_fc1_bias.npy`);
    const fc2 = await readNpyFull(`${MODEL_DIR}/embeddings/text_projection_fc2_weight.npy`); // (1024,2048)
    const fc2b = await readNpyFull(`${MODEL_DIR}/embeddings/text_projection_fc2_bias.npy`);
    const hiddenDim = fc1.shape[0]; // 2048
    const outDim = fc2.shape[0]; // 1024
    const n = tokenIds.length;
    const out = new Float32Array(n * outDim);
    const hidden = new Float32Array(hiddenDim);
    for (let t = 0; t < n; t++) {
      const rowOffset = t * inDim;
      for (let h = 0; h < hiddenDim; h++) {
        let acc = fc1b.data[h];
        const wOffset = h * inDim;
        for (let i = 0; i < inDim; i++) acc += rows[rowOffset + i] * fc1.data[wOffset + i];
        hidden[h] = acc / (1 + Math.exp(-acc)); // SiLU
      }
      for (let o = 0; o < outDim; o++) {
        let acc = fc2b.data[o];
        const wOffset = o * hiddenDim;
        for (let i = 0; i < hiddenDim; i++) acc += hidden[i] * fc2.data[wOffset + i];
        out[t * outDim + o] = acc;
      }
    }
    return out; // Float32Array [n, outDim]
  }

  function addVectorsInPlace(dst, src) {
    for (let i = 0; i < dst.length; i++) dst[i] += src[i];
  }

  // ---------------------------------------------------------------------
  // KV cache container helpers (talker: 28 layers stacked into one [28,...]
  // buffer per notes; code predictor: 5 layers stacked into [5,...]).
  // ---------------------------------------------------------------------
  function emptyCache(layers) {
    return { keys: new Float32Array(0), values: new Float32Array(0), layers, seq: 0 };
  }

  async function runTalkerPrefill(sessionId, embeds, seqLen) {
    const attnMask = new Array(seqLen).fill(1);
    const posIds = [];
    for (let axis = 0; axis < 3; axis++) for (let i = 0; i < seqLen; i++) posIds.push(i);
    const embedsT = await makeF32Tensor(sessionId, [1, seqLen, TALKER_HIDDEN], embeds);
    const maskT = await makeI64Tensor(sessionId, [1, seqLen], attnMask);
    const posT = await makeI64Tensor(sessionId, [3, 1, seqLen], posIds);
    const raw = await state.sdk.onnx.run(sessionId, { inputs_embeds: embedsT, attention_mask: maskT, position_ids: posT });
    await state.sdk.onnx.disposeTensor(embedsT);
    await state.sdk.onnx.disposeTensor(maskT);
    await state.sdk.onnx.disposeTensor(posT);
    const names = ['logits', 'hidden_states'];
    for (let l = 0; l < TALKER_LAYERS; l++) names.push(`present_key_${l}`, `present_value_${l}`);
    return normalizeOutputs(sessionId, raw, names); // { logits, hidden_states, present_key_0..27, present_value_0..27 }, each { data, dims }
  }

  function stackPresentKV(result, layers) {
    // Per-layer present_key_i / present_value_i (each [1,8,seq,128]) -> stacked [layers,1,8,seq,128]
    let keys = null, values = null, perLayerLen = null, seq = null;
    for (let l = 0; l < layers; l++) {
      const k = result[`present_key_${l}`];
      const v = result[`present_value_${l}`];
      if (!perLayerLen) {
        perLayerLen = k.data.length;
        keys = new Float32Array(perLayerLen * layers);
        values = new Float32Array(perLayerLen * layers);
        seq = k.dims[k.dims.length - 2]; // [.., seq, 128] -> second-to-last dim
      }
      keys.set(k.data, l * perLayerLen);
      values.set(v.data, l * perLayerLen);
    }
    return { keys, values, layers, seq };
  }

  async function runTalkerDecode(sessionId, embed1024, cache, totalSeq) {
    const posIds = [totalSeq - 1, totalSeq - 1, totalSeq - 1];
    const attnMask = new Array(totalSeq).fill(1);
    const embedsT = await makeF32Tensor(sessionId, [1, 1, TALKER_HIDDEN], embed1024);
    const maskT = await makeI64Tensor(sessionId, [1, totalSeq], attnMask);
    const posT = await makeI64Tensor(sessionId, [3, 1, 1], posIds);
    const pastK = await makeF32Tensor(sessionId, [TALKER_LAYERS, 1, TALKER_KV_HEADS, cache.seq, HEAD_DIM], cache.keys);
    const pastV = await makeF32Tensor(sessionId, [TALKER_LAYERS, 1, TALKER_KV_HEADS, cache.seq, HEAD_DIM], cache.values);
    const raw = await state.sdk.onnx.run(sessionId, {
      inputs_embeds: embedsT, attention_mask: maskT, position_ids: posT, past_keys: pastK, past_values: pastV
    });
    for (const t of [embedsT, maskT, posT, pastK, pastV]) await state.sdk.onnx.disposeTensor(t);
    return normalizeOutputs(sessionId, raw, ['logits', 'hidden_states', 'present_keys', 'present_values']);
  }

  async function runCodePredictorStep(sessionId, embedsSeq, seqLen, step, cache) {
    const embedsT = await makeF32Tensor(sessionId, [1, seqLen, TALKER_HIDDEN], embedsSeq);
    const stepT = await makeI64Tensor(sessionId, [1], [step]);
    const pastK = await makeF32Tensor(sessionId, [CP_LAYERS, 1, TALKER_KV_HEADS, cache.seq, HEAD_DIM], cache.keys);
    const pastV = await makeF32Tensor(sessionId, [CP_LAYERS, 1, TALKER_KV_HEADS, cache.seq, HEAD_DIM], cache.values);
    const raw = await state.sdk.onnx.run(sessionId, { inputs_embeds: embedsT, generation_steps: stepT, past_keys: pastK, past_values: pastV });
    for (const t of [embedsT, stepT, pastK, pastV]) await state.sdk.onnx.disposeTensor(t);
    return normalizeOutputs(sessionId, raw, ['logits', 'present_keys', 'present_values']);
  }

  // ---------------------------------------------------------------------
  // Sampling (per Rust reference behavior documented in 調査ノート.md)
  // ---------------------------------------------------------------------
  function softmaxSampleTopK(logits, { temperature, topK, repetitionPenalty, previousIds, maskRange, forceEosBlock, eosId }) {
    const n = logits.length;
    const scores = Float32Array.from(logits);
    if (maskRange) {
      for (let i = 0; i < n; i++) {
        const inRange = i >= maskRange[0] && i <= maskRange[1];
        const isEos = eosId !== undefined && i === eosId;
        if (!inRange || (forceEosBlock && isEos)) scores[i] = -Infinity;
      }
    }
    if (repetitionPenalty && repetitionPenalty !== 1.0 && previousIds && previousIds.length) {
      const seen = new Set(previousIds);
      for (const id of seen) {
        if (id < 0 || id >= n || scores[id] === -Infinity) continue;
        scores[id] = scores[id] > 0 ? scores[id] / repetitionPenalty : scores[id] * repetitionPenalty;
      }
    }
    for (let i = 0; i < n; i++) scores[i] = scores[i] === -Infinity ? -Infinity : scores[i] / temperature;
    const indices = Array.from({ length: n }, (_, i) => i).filter((i) => scores[i] !== -Infinity);
    indices.sort((a, b) => scores[b] - scores[a]);
    const top = indices.slice(0, Math.min(topK, indices.length));
    const maxLogit = scores[top[0]];
    const exps = top.map((i) => Math.exp(scores[i] - maxLogit));
    const sum = exps.reduce((a, b) => a + b, 0);
    let r = Math.random() * sum, cum = 0;
    for (let i = 0; i < top.length; i++) {
      cum += exps[i];
      if (r <= cum) return top[i];
    }
    return top[top.length - 1];
  }

  // ---------------------------------------------------------------------
  // WAV encoding of a float32 PCM waveform
  // ---------------------------------------------------------------------
  function encodeWav(samples, sampleRate) {
    const bytesPerSample = 2;
    const blockAlign = bytesPerSample;
    const buffer = new ArrayBuffer(44 + samples.length * bytesPerSample);
    const view = new DataView(buffer);
    const writeStr = (offset, str) => { for (let i = 0; i < str.length; i++) view.setUint8(offset + i, str.charCodeAt(i)); };
    writeStr(0, 'RIFF');
    view.setUint32(4, 36 + samples.length * bytesPerSample, true);
    writeStr(8, 'WAVE');
    writeStr(12, 'fmt ');
    view.setUint32(16, 16, true);
    view.setUint16(20, 1, true); // PCM
    view.setUint16(22, 1, true); // mono
    view.setUint32(24, sampleRate, true);
    view.setUint32(28, sampleRate * blockAlign, true);
    view.setUint16(32, blockAlign, true);
    view.setUint16(34, 16, true);
    writeStr(36, 'data');
    view.setUint32(40, samples.length * bytesPerSample, true);
    let offset = 44;
    for (let i = 0; i < samples.length; i++) {
      const s = Math.max(-1, Math.min(1, samples[i]));
      view.setInt16(offset, s < 0 ? s * 0x8000 : s * 0x7fff, true);
      offset += bytesPerSample;
    }
    return new Blob([buffer], { type: 'audio/wav' });
  }

  function setProgress(title, detail, value) {
    $('progressPanel').hidden = false;
    $('progressTitle').textContent = title;
    $('progressDetail').textContent = detail;
    $('progressValue').textContent = `${Math.round(value)}%`;
    $('progressFill').style.width = `${Math.max(0, Math.min(100, value))}%`;
  }

  function setFileProgress(path, percent, status) {
    const item = state.fileProgress.get(path);
    if (!item) return;
    item.percent = Math.max(0, Math.min(100, percent));
    item.status = status;
    item.fill.style.width = `${item.percent}%`;
    item.percentLabel.textContent = `${Math.round(item.percent)}%`;
    item.stateLabel.textContent = status;
    item.row.classList.toggle('active', status === '取得中' || status === '再開中');
  }

  function prepareFileProgress(files) {
    const list = $('fileProgressList');
    list.replaceChildren();
    state.fileProgress = new Map();
    files.forEach((path) => {
      const row = document.createElement('div');
      row.className = 'file-progress-item';
      const name = document.createElement('span');
      name.className = 'file-progress-name';
      name.textContent = path;
      name.title = path;
      const percentLabel = document.createElement('span');
      percentLabel.className = 'file-progress-percent';
      const stateLabel = document.createElement('span');
      stateLabel.className = 'file-progress-state';
      stateLabel.textContent = '待機中';
      const track = document.createElement('div');
      track.className = 'file-progress-track';
      const fill = document.createElement('div');
      fill.className = 'file-progress-fill';
      track.append(fill);
      row.append(name, percentLabel, stateLabel, track);
      list.append(row);
      state.fileProgress.set(path, { row, fill, percentLabel, stateLabel, percent: 0, status: '待機中' });
      setFileProgress(path, 0, '待機中');
    });
  }

  function updateOverallProgress(index, filePercent, total) {
    const overall = ((index - 1) + filePercent / 100) / total * 100;
    setProgress('モデルを取得中', `${index} / ${total} ファイル`, overall);
  }

  async function cancelPendingEmbeddingDownloads() {
    if (!state.sdk?.download?.list) return;
    const downloads = await state.sdk.download.list();
    for (const download of downloads) {
      const isEmbedding = typeof download.destPath === 'string' && download.destPath.includes(`${MODEL_DIR}/embeddings/`);
      const isActive = ['pending', 'running', 'paused'].includes(download.state);
      if (isEmbedding && isActive) await state.sdk.download.cancel(download.id);
    }
  }

  function setEngine(heading, detail) {
    $('engineState').textContent = heading;
    $('engineDetail').textContent = detail;
  }

  function updateCharCount() {
    $('charCount').textContent = `${$('targetText').value.length} / 800`;
  }

  function setAudio(file) {
    if (!file) return;
    if (!file.type.startsWith('audio/')) {
      setEngine('音声を確認できません', '音声ファイルを選択してください。');
      return;
    }
    if (state.audioUrl) URL.revokeObjectURL(state.audioUrl);
    state.audioFile = file;
    state.audioUrl = URL.createObjectURL(file);
    $('referencePreview').src = state.audioUrl;
    $('referencePreview').hidden = false;
    $('audioFileName').textContent = file.name;
    $('audioStatus').classList.add('ready');
  }

  async function exists(path) {
    try { return await state.sdk.files.exists(path); } catch { return false; }
  }

  async function requiredFiles() {
    const variant = $('variant').value;
    return [...COMMON_FILES, ...VARIANT_FILES[variant], ...EMBEDDING_FILES];
  }

  async function checkModel() {
    if (!state.sdk) return false;
    const files = await requiredFiles();
    let present = 0;
    for (const path of files) if (await exists(`${MODEL_DIR}/${path}`)) present++;
    const ready = present === files.length;
    state.modelReady = ready;
    $('generateButton').disabled = !ready;
    if (ready) setEngine('モデル準備完了', `${$('variant').value.toUpperCase()} / ${files.length}ファイルを確認済み。`);
    else setEngine('モデル未準備', `${present} / ${files.length}ファイルが端末にあります。`);
    return ready;
  }

  function subscribeDownloadEvents() {
    return () => {};
  }

  async function downloadFile(path, index, total) {
    const destination = `${MODEL_DIR}/${path}`;
    if (await exists(destination)) {
      setFileProgress(path, 100, '完了');
      updateOverallProgress(index, 100, total);
      return;
    }
    setFileProgress(path, 0, '準備中');
    const entry = await state.sdk.download.create({ url: HF_ROOT + path, destPath: destination });
    await state.sdk.download.start(entry.id);
    let polls = 0;
    let resumed = false;
    for (;;) {
      const current = await state.sdk.download.get(entry.id);
      const fileProgress = current.totalBytes > 0 ? current.bytesDownloaded / current.totalBytes : 0;
      const percent = fileProgress * 100;
      const status = current.state === 'running' ? '取得中' : current.state === 'pending' ? '待機中' : current.state;
      setFileProgress(path, percent, status);
      updateOverallProgress(index, percent, total);
      if (current.state === 'completed') {
        setFileProgress(path, 100, '完了');
        updateOverallProgress(index, 100, total);
        return;
      }
      if (current.state === 'failed' || current.state === 'cancelled') {
        setFileProgress(path, percent, current.state === 'failed' ? '失敗' : 'キャンセル');
        throw new Error(`${path}: ${current.error || current.state}`);
      }
      if (current.state === 'paused') {
        if (resumed) throw new Error(`${path}: ダウンロードが一時停止したままです`);
        resumed = true;
        setFileProgress(path, percent, '再開中');
        await state.sdk.download.resume(entry.id);
      }
      polls++;
      if (polls > 3000) throw new Error(`${path}: ダウンロードがタイムアウトしました`);
      await new Promise((resolve) => setTimeout(resolve, 700));
    }
  }

  async function prepareModel() {
    if (!state.sdk) throw new Error('Nezumi Runtimeで起動してください。');
    if (state.busy) return;
    state.busy = true;
    $('downloadButton').disabled = true;
    const files = await requiredFiles();
    prepareFileProgress(files);
    setProgress('モデルを確認中', `0 / ${files.length} ファイル`, 0);
    const off = subscribeDownloadEvents();
    try {
      await cancelPendingEmbeddingDownloads();
      for (let index = 0; index < files.length; index++) await downloadFile(files[index], index + 1, files.length);
      setProgress('モデル準備完了', 'ONNX・外部データ・Tokenizerを確認しました。埋め込みは必要時に取得します。', 100);
      await checkModel();
    } finally {
      off?.();
      $('downloadButton').disabled = false;
      state.busy = false;
    }
  }

  async function decodeReference() {
    if (!state.audioFile) throw new Error('参照音声を選択してください。');
    const context = new AudioContext();
    try {
      const buffer = await context.decodeAudioData(await state.audioFile.arrayBuffer());
      const channels = Array.from({ length: buffer.numberOfChannels }, (_, index) => buffer.getChannelData(index));
      const length = Math.min(buffer.length, Math.floor(buffer.sampleRate * 10));
      const mono = new Float32Array(length);
      for (let index = 0; index < length; index++) {
        for (const channel of channels) mono[index] += channel[index] || 0;
        mono[index] /= channels.length;
      }
      return { samples: mono, sampleRate: buffer.sampleRate };
    } finally {
      await context.close();
    }
  }

  function resampleTo(samples, fromRate, toRate) {
    if (fromRate === toRate) return samples;
    const ratio = toRate / fromRate;
    const outLen = Math.round(samples.length * ratio);
    const out = new Float32Array(outLen);
    for (let i = 0; i < outLen; i++) {
      const srcPos = i / ratio;
      const i0 = Math.floor(srcPos), i1 = Math.min(i0 + 1, samples.length - 1);
      const frac = srcPos - i0;
      out[i] = samples[i0] * (1 - frac) + samples[i1] * frac;
    }
    return out;
  }

  // The tokenizer_encoder expects a fixed 240000-sample (10s @ 24kHz) window;
  // pad short references with silence, trim long ones.
  function fitTokenizerWindow(samples24k) {
    const need = 240000;
    if (samples24k.length === need) return samples24k;
    const out = new Float32Array(need);
    out.set(samples24k.subarray(0, Math.min(need, samples24k.length)));
    return out;
  }

  async function runTokenizerEncoder(samples24k) {
    const sessionId = await openSession('tokenizer_encoder', 'tokenizer_encoder.onnx');
    const windowed = fitTokenizerWindow(samples24k);
    const t = await makeF32Tensor(sessionId, [1, 240000], windowed);
    const raw = await state.sdk.onnx.run(sessionId, { waveform: t });
    await state.sdk.onnx.disposeTensor(t);
    const result = await normalizeOutputs(sessionId, raw, ['audio_codes'], { audio_codes: { shapeOptional: true } });
    // audio_codes: [1,16,frames_dynamic]; reference trims to ceil(original_samples/1920)
    const wantedFrames = Math.max(1, Math.ceil(samples24k.length / SAMPLES_PER_FRAME));
    const dims = result.audio_codes.dims; // may be null/partially symbolic
    const actualFrames = (dims && dims[2]) || Math.round(result.audio_codes.data.length / 16);
    const frames = Math.min(actualFrames, wantedFrames);
    const codes = []; // [16][frames]
    for (let g = 0; g < 16; g++) {
      const row = new Array(frames);
      for (let f = 0; f < frames; f++) row[f] = Number(result.audio_codes.data[g * actualFrames + f]);
      codes.push(row);
    }
    return codes;
  }

  async function runSpeakerEncoder(samples24k) {
    const sessionId = await openSession('speaker_encoder', 'speaker_encoder.onnx');
    const { data, frames, nMels } = computeMelSpectrogram(samples24k);
    const t = await makeF32Tensor(sessionId, [1, frames, nMels], data);
    const raw = await state.sdk.onnx.run(sessionId, { mels: t });
    await state.sdk.onnx.disposeTensor(t);
    const result = await normalizeOutputs(sessionId, raw, ['speaker_embedding'], {
      speaker_embedding: { shapeOptional: true }
    });
    const embedding = Float32Array.from(result.speaker_embedding.data);
    if (embedding.length !== TALKER_HIDDEN) {
      throw new Error(`話者埋め込みの要素数が不正です: ${embedding.length}（期待値: ${TALKER_HIDDEN}）`);
    }
    return embedding; // [1024]
  }

  function variantPath(name) {
    return `${$('variant').value}/${name}`;
  }

  async function buildIclSequence(referenceCodes, referenceTextIds, targetTextIds, speakerEmbedding, embeddings) {
    // Text side: [im_start, assistant, "\n"] role prefix (projected) is fixed
    // header content baked via tokenizer ids; codec prefix [think, think_bos,
    // language, think_eos] is appended to tts_pad slots; then speaker slot
    // tts_pad + speaker_embedding; transition tts_bos + codec_pad; ICL text
    // side [text_proj(ref_tokens ++ text_tokens), tts_eos] + codec_pad; codec
    // side [codec_bos, per-ref-frame sum(group0 talker embed + groups1..15 cp
    // embeds)] + tts_pad. Concatenate text side then codec side.
    // role prefix tokens: im_start, "assistant", "\n" -- resolved via tokenizer text encode
    const tk = await loadTokenizer();
    const assistantNl = tokenizeText('assistant\n', tk);
    const roleIds = [TOK.im_start, ...assistantNl];
    const roleEmbeds = await projectTextTokens(roleIds);

    const allTextIds = [...referenceTextIds, ...targetTextIds];
    const textProj = await projectTextTokens(allTextIds);
    const eosProj = await projectTextTokens([TOK.tts_eos]);

    const tpEmb = embeddings.tpEmb; // talker_codec_embedding rows lookup fn
    const cpEmb = embeddings.cpEmb; // per-group cp_codec_embedding rows lookup fn

    // codec prefix tokens (think / think_bos / language / think_eos) if resolvable, else skip gracefully
    const codecPrefixIds = [TOK.think, TOK.think_bos, TOK.language, TOK.think_eos].filter((v) => v !== null && v !== undefined);
    const codecPrefixEmbeds = codecPrefixIds.length ? await tpEmb(codecPrefixIds) : new Float32Array(0);

    const ttsPadEmb = (await tpEmb([TOK.tts_pad]));
    const ttsBosEmb = (await tpEmb([TOK.tts_bos]));
    const codecBosEmb = (await tpEmb([TOK.tts_bos])); // codec_bos shares talker embedding table per notes' "codec side" wording

    const parts = [];
    parts.push(roleEmbeds);
    if (codecPrefixEmbeds.length) parts.push(codecPrefixEmbeds);
    parts.push(ttsPadEmb);
    addVectorsInPlace(parts[parts.length - 1], speakerEmbedding);
    parts.push(ttsBosEmb);
    parts.push(textProj);
    parts.push(eosProj);

    // codec side: codec_bos, then per reference frame: sum(group0 talker embed + groups1..15 cp embeds)
    parts.push(codecBosEmb);
    const refFrames = referenceCodes[0].length;
    for (let f = 0; f < refFrames; f++) {
      const group0Id = referenceCodes[0][f];
      const frameEmb = await tpEmb([group0Id]);
      for (let g = 1; g < CODE_GROUPS; g++) {
        const gid = referenceCodes[g][f];
        const gEmb = await cpEmb(g - 1, [gid]);
        addVectorsInPlace(frameEmb, gEmb);
      }
      parts.push(frameEmb);
    }
    parts.push(ttsPadEmb);

    let total = 0;
    for (const p of parts) total += p.length;
    const seqLen = total / TALKER_HIDDEN;
    const flat = new Float32Array(total);
    let off = 0;
    for (const p of parts) { flat.set(p, off); off += p.length; }
    return { embeds: flat, seqLen };
  }

  async function makeEmbeddingLookups() {
    const tpHeader = await readNpyHeaderOnly(`${MODEL_DIR}/embeddings/talker_codec_embedding.npy`);
    const cpHeaders = [];
    for (let g = 0; g < 15; g++) cpHeaders.push(await readNpyHeaderOnly(`${MODEL_DIR}/embeddings/cp_codec_embedding_${g}.npy`));
    return {
      tpEmb: async (ids) => readNpyRows(`${MODEL_DIR}/embeddings/talker_codec_embedding.npy`, tpHeader, ids),
      cpEmb: async (groupIdx, ids) => readNpyRows(`${MODEL_DIR}/embeddings/cp_codec_embedding_${groupIdx}.npy`, cpHeaders[groupIdx], ids)
    };
  }

  async function predictCodeGroupsForFrame(talkerHidden, group0Id, embeddings, generateConfig) {
    // Code predictor: start seq=[talker_hidden, group0_embed] (len 2), then
    // 15 more calls total, cache grows 2,3,...,16. generation_steps selects
    // CP LM head 0..14 for each call.
    const sessionId = await openSession('code_predictor', variantPath('code_predictor.onnx'));
    const group0Emb = await embeddings.tpEmb([group0Id]);
    let seqEmbeds = new Float32Array(TALKER_HIDDEN * 2);
    seqEmbeds.set(talkerHidden, 0);
    seqEmbeds.set(group0Emb, TALKER_HIDDEN);
    let cache = emptyCache(CP_LAYERS);
    const groups = [group0Id];
    let curSeqLen = 2;
    for (let step = 0; step < 15; step++) {
      const result = await runCodePredictorStep(sessionId, seqEmbeds, curSeqLen, step, cache);
      const logitsDims = result.logits.dims; // [1, seqLen, 2048]
      const lastOffset = (logitsDims[1] - 1) * CP_VOCAB;
      const lastLogits = result.logits.data.subarray(lastOffset, lastOffset + CP_VOCAB);
      const pick = softmaxSampleTopK(lastLogits, {
        temperature: generateConfig.subTemperature, topK: generateConfig.subTopK, repetitionPenalty: 1.0, previousIds: [], maskRange: null
      });
      groups.push(pick);
      const seq = result.present_keys.dims[result.present_keys.dims.length - 2];
      cache = { keys: Float32Array.from(result.present_keys.data), values: Float32Array.from(result.present_values.data), layers: CP_LAYERS, seq };
      if (step < 14) {
        const nextEmb = await embeddings.cpEmb(step, [pick]);
        seqEmbeds = nextEmb; // subsequent calls feed exactly the newly selected embedding
        curSeqLen = 1;
      }
    }
    return groups; // [group0..group15], length 16
  }

  async function generate() {
    if (!state.modelReady || state.busy) return;
    if (!$('referenceText').value.trim()) throw new Error('参照音声の書き起こしを入力してください。');
    if (!$('targetText').value.trim()) throw new Error('生成する文章を入力してください。');
    state.busy = true;
    $('generateButton').disabled = true;
    try {
      setProgress('参照音声を解析中', '音声を読み込んでいます。', 5);
      const reference = await decodeReference();
      if (reference.samples.length < reference.sampleRate * 2) throw new Error('参照音声は2秒以上にしてください。');
      const samples24k = resampleTo(reference.samples, reference.sampleRate, SAMPLE_RATE);

      setProgress('参照音声を解析中', '音声トークンを抽出しています。', 15);
      const referenceCodes = await runTokenizerEncoder(samples24k);

      setProgress('話者を解析中', '話者埋め込みを計算しています。', 25);
      const speakerEmbedding = await runSpeakerEncoder(samples24k);

      setProgress('テキストを解析中', 'トークナイザーを読み込んでいます。', 32);
      const tokenizer = await loadTokenizer();
      const referenceTextIds = tokenizeText($('referenceText').value.trim(), tokenizer);
      const targetTextIds = tokenizeText($('targetText').value.trim(), tokenizer);

      setProgress('推論準備中', 'モデル準備時に取得した埋め込みテーブルを参照しています。', 40);
      const embeddings = await makeEmbeddingLookups();
      const { embeds, seqLen } = await buildIclSequence(referenceCodes, referenceTextIds, targetTextIds, speakerEmbedding, embeddings);

      setProgress('推論中', 'Talkerモデルをプリフィルしています。', 48);
      const prefillSession = await openSession('talker_prefill', variantPath('talker_prefill.onnx'));
      const prefillResult = await runTalkerPrefill(prefillSession, embeds, seqLen);
      let cache = stackPresentKV(prefillResult, TALKER_LAYERS);
      let totalSeq = seqLen;

      const decodeSession = await openSession('talker_decode', variantPath('talker_decode.onnx'));
      const generateConfig = { temperature: 0.9, topK: 50, repetitionPenalty: 1.05, subTemperature: 0.9, subTopK: 50 };

      const lastHiddenOffset = (prefillResult.hidden_states.dims[1] - 1) * TALKER_HIDDEN;
      let curTalkerHidden = Float32Array.from(prefillResult.hidden_states.data.subarray(lastHiddenOffset, lastHiddenOffset + TALKER_HIDDEN));
      let lastLogits = prefillResult.logits.data.subarray((prefillResult.logits.dims[1] - 1) * TALKER_VOCAB, prefillResult.logits.dims[1] * TALKER_VOCAB);

      const allFrameCodes = []; // list of [16] group ids
      const previousGroup0Ids = [];
      let frameIndex = 0;

      // Sum of all 16 codec embeddings for the previous frame + tts_pad becomes next talker input (non-streaming decode).
      const ttsPadEmb = await embeddings.tpEmb([TOK.tts_pad]);

      for (;;) {
        const forceEosBlock = frameIndex < 2;
        const group0 = softmaxSampleTopK(lastLogits, {
          temperature: generateConfig.temperature, topK: generateConfig.topK, repetitionPenalty: generateConfig.repetitionPenalty,
          previousIds: previousGroup0Ids, maskRange: [TALKER_MASK_LOW, TALKER_MASK_HIGH], forceEosBlock, eosId: TALKER_EOS_ID
        });
        const absoluteGroup0 = group0; // logits already indexed over full talker vocab
        if (absoluteGroup0 === TALKER_EOS_ID && frameIndex >= 2) break;
        if (frameIndex >= MAX_NEW_FRAMES) break;

        previousGroup0Ids.push(absoluteGroup0);
        setProgress('音声を生成中', `フレーム ${frameIndex + 1} を生成しています。`, Math.min(48 + frameIndex * 0.6, 92));

        const groups = await predictCodeGroupsForFrame(curTalkerHidden, absoluteGroup0 - TALKER_MASK_LOW, embeddings, generateConfig);
        allFrameCodes.push(groups);

        // Build next talker input: sum of all 16 codec embeddings for this frame + tts_pad
        let nextEmb = await embeddings.tpEmb([absoluteGroup0 - TALKER_MASK_LOW]);
        for (let g = 1; g < CODE_GROUPS; g++) {
          const gEmb = await embeddings.cpEmb(g - 1, [groups[g]]);
          addVectorsInPlace(nextEmb, gEmb);
        }
        addVectorsInPlace(nextEmb, ttsPadEmb);

        totalSeq += 1;
        const decodeResult = await runTalkerDecode(decodeSession, nextEmb, cache, totalSeq);
        cache = { keys: Float32Array.from(decodeResult.present_keys.data), values: Float32Array.from(decodeResult.present_values.data), layers: TALKER_LAYERS, seq: totalSeq };
        curTalkerHidden = Float32Array.from(decodeResult.hidden_states.data);
        lastLogits = decodeResult.logits.data;
        frameIndex += 1;
      }

      if (allFrameCodes.length === 0) throw new Error('音声フレームを生成できませんでした。参照音声やテキストを見直してください。');

      setProgress('波形を合成中', 'Vocoderで波形を生成しています。', 94);
      const numFrames = allFrameCodes.length;
      const codesFlat = new Array(CODE_GROUPS * numFrames);
      for (let g = 0; g < CODE_GROUPS; g++) for (let f = 0; f < numFrames; f++) codesFlat[g * numFrames + f] = allFrameCodes[f][g];
      const vocoderSession = await openSession('vocoder', variantPath('vocoder.onnx'));
      const codesT = await makeI64Tensor(vocoderSession, [1, CODE_GROUPS, numFrames], codesFlat);
      const rawVocoder = await state.sdk.onnx.run(vocoderSession, { codes: codesT });
      await state.sdk.onnx.disposeTensor(codesT);
      const vocoderResult = await normalizeOutputs(vocoderSession, rawVocoder, ['waveform'], { waveform: { shapeOptional: true } });
      const waveform = Float32Array.from(vocoderResult.waveform.data);

      setProgress('完了', '音声ファイルを書き出しています。', 99);
      const blob = encodeWav(waveform, SAMPLE_RATE);
      const url = URL.createObjectURL(blob);
      $('resultAudio').src = url;
      $('resultPanel').hidden = false;
      $('resultMeta').textContent = `${numFrames}フレーム（約${(waveform.length / SAMPLE_RATE).toFixed(1)}秒） / ${$('variant').value.toUpperCase()}`;
      setProgress('完了', '音声を生成しました。', 100);
    } catch (error) {
      console.error('Qwen3-TTS generation failed', error);
      setProgress('生成に失敗しました', error.message, 0);
    } finally {
      await closeAllSessions();
      state.busy = false;
      $('generateButton').disabled = !state.modelReady;
    }
  }

  function wireUi() {
    $('referenceAudio').addEventListener('change', (event) => setAudio(event.target.files[0]));
    $('dropzone').addEventListener('dragover', (event) => { event.preventDefault(); $('dropzone').classList.add('dragging'); });
    $('dropzone').addEventListener('dragleave', () => $('dropzone').classList.remove('dragging'));
    $('dropzone').addEventListener('drop', (event) => { event.preventDefault(); $('dropzone').classList.remove('dragging'); setAudio(event.dataTransfer.files[0]); });
    $('targetText').addEventListener('input', updateCharCount);
    $('variant').addEventListener('change', () => checkModel());
    $('downloadButton').addEventListener('click', () => prepareModel().catch((error) => setProgress('準備に失敗しました', error.message, 0)));
    $('generateButton').addEventListener('click', () => generate());
    $('downloadAudioButton').addEventListener('click', () => $('resultAudio').src && Object.assign(document.createElement('a'), { href: $('resultAudio').src, download: 'qwen3-tts.wav' }).click());
    updateCharCount();
  }

  async function boot() {
    wireUi();
    if (!state.sdk) {
      $('runtimePill').textContent = 'Nezumi Runtimeが必要';
      setEngine('プレビュー mode', 'このMini AppはNezumi AI本体から起動してください。');
      $('downloadButton').disabled = true;
      return;
    }
    $('runtimePill').textContent = 'Nezumi Runtime接続済み';
    await checkModel();
  }

  boot().catch((error) => setEngine('初期化に失敗しました', error.message));
})();
