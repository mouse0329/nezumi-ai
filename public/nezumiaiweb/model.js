'use strict';

// ===== model.js =====
// OPFSにモデルをキャッシュ・管理する

window.modelManager = (() => {
  const MODEL_CONFIGS = {
    'gemma4-e2b': {
      name: 'Gemma 4 E2B',
      description: 'Gemma 4 系の軽量モデル',
      localUrl: './models/gemma-4-E2B-it-web.task',
      url: 'https://huggingface.co/litert-community/gemma-4-E2B-it-litert-lm/resolve/main/gemma-4-E2B-it-web.task?download=true',
      filename: 'gemma-4-E2B-it-web.task',
      legacyFilename: 'gemma-4-E2B-it-int4-Web.task',
      maxTokens: 4096,
      sizeMB: 2004,
    },
    'gemma4-e4b': {
      name: 'Gemma 4 E4B',
      description: 'より高品質な Gemma 4 モデル',
      localUrl: './models/gemma-4-E4B-it-web.task',
      url: 'https://huggingface.co/litert-community/gemma-4-E4B-it-litert-lm/resolve/main/gemma-4-E4B-it-web.task?download=true',
      filename: 'gemma-4-E4B-it-web.task',
      legacyFilename: 'gemma-4-E4B-it-int4-Web.task',
      maxTokens: 4096,
      sizeMB: 3000,
    },
  };

  // OPFSのルートディレクトリ取得
  async function getDir() {
    return await navigator.storage.getDirectory();
  }

  async function getExistingModelFileHandle(modelKey) {
    const dir = await getDir();
    const config = MODEL_CONFIGS[modelKey];
    const filenames = [config.filename, config.legacyFilename].filter(Boolean);
    for (const filename of filenames) {
      try {
        return await dir.getFileHandle(filename, { create: false });
      } catch { }
    }
    throw new Error('モデルファイルが見つかりません');
  }

  // モデルがOPFSにキャッシュ済みか確認
  async function isModelCached(modelKey) {
    try {
      const fileHandle = await getExistingModelFileHandle(modelKey);
      const file = await fileHandle.getFile();
      return file.size > 0;
    } catch {
      return false;
    }
  }

  // OPFS上のモデルファイル情報を取得
  async function getModelInfo(modelKey) {
    try {
      const fileHandle = await getExistingModelFileHandle(modelKey);
      const file = await fileHandle.getFile();
      return {
        cached: file.size > 0,
        size: file.size,
        updatedAt: file.lastModified ? new Date(file.lastModified) : null,
      };
    } catch {
      return {
        cached: false,
        size: 0,
        updatedAt: null,
      };
    }
  }

  // OPFSからReadableStreamDefaultReaderを取得（LlmInferenceに渡す用）
  async function getModelReader(modelKey) {
    const fileHandle = await getExistingModelFileHandle(modelKey);
    const file = await fileHandle.getFile();
    return file.stream().getReader();
  }

  async function writeReaderToOpfs(modelKey, reader, total, onProgress) {
    const config = MODEL_CONFIGS[modelKey];
    const dir = await getDir();
    const fileHandle = await dir.getFileHandle(config.filename, { create: true });
    const writable = await fileHandle.createWritable();

    let loaded = 0;
    try {
      while (true) {
        const { done, value } = await reader.read();
        if (done) break;
        await writable.write(value);
        loaded += value.byteLength;
        const percent = total ? Math.min(100, Math.round((loaded / total) * 100)) : 0;
        onProgress?.(loaded, total, percent);
      }
      await writable.close();
    } catch (e) {
      try { await writable.abort(); } catch { }
      throw e;
    }
  }

  async function fetchModelFrom(url, signal) {
    const response = await fetch(url, { signal, mode: 'cors', credentials: 'omit' });
    if (!response.ok) throw new Error(`HTTP ${response.status}`);
    if (!response.body) throw new Error('レスポンス本文を読み取れません');
    return response;
  }

  // モデルをダウンロードしてOPFSに保存
  // onProgress(loaded, total, percent) を呼び続ける
  async function downloadModel(modelKey, onProgress, signal) {
    const config = MODEL_CONFIGS[modelKey];
    const candidates = [config.localUrl, config.url].filter(Boolean);
    const errors = [];

    for (const url of candidates) {
      try {
        const response = await fetchModelFrom(url, signal);
        const total = parseInt(response.headers.get('content-length') || '0', 10);
        await writeReaderToOpfs(modelKey, response.body.getReader(), total, onProgress);
        return;
      } catch (e) {
        if (e.name === 'AbortError') throw e;
        errors.push(`${url}: ${e.message}`);
      }
    }

    throw new Error(
      'モデルの自動ダウンロードに失敗しました。配布元のCORS制限の可能性があります。' +
      'Hugging Faceから .task ファイルを保存し、「ファイルから追加」で取り込んでください。' +
      ` 詳細: ${errors.join(' / ')}`
    );
  }

  async function importModelFile(modelKey, file, onProgress) {
    if (!file) return;
    const config = MODEL_CONFIGS[modelKey];
    if (!file.name.endsWith('.task')) {
      throw new Error('.task ファイルを選択してください');
    }
    if (file.size < 1024 * 1024) {
      throw new Error('選択したファイルが小さすぎます');
    }
    await writeReaderToOpfs(modelKey, file.stream().getReader(), file.size, onProgress);
  }

  // OPFSからモデルを削除
  async function deleteModel(modelKey) {
    const dir = await getDir();
    const filenames = [MODEL_CONFIGS[modelKey].filename, MODEL_CONFIGS[modelKey].legacyFilename].filter(Boolean);
    await Promise.all(filenames.map(async filename => {
      try { await dir.removeEntry(filename); } catch { }
    }));
  }

  // OPFS使用量（バイト）
  async function getStorageUsage() {
    try {
      const est = await navigator.storage.estimate();
      return est.usage || 0;
    } catch {
      return 0;
    }
  }

  return {
    MODEL_CONFIGS,
    isModelCached,
    getModelInfo,
    getModelReader,
    downloadModel,
    importModelFile,
    deleteModel,
    getStorageUsage,
  };
})();
