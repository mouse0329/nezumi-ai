'use strict';

// ===== model.js =====
// OPFSにモデルをキャッシュ・管理する

window.modelManager = (() => {
  const MODEL_CONFIGS = {
    'gemma4-e2b': {
      name: 'Gemma 4 E2B',
      description: 'Gemma 4 系の軽量モデル',
      url: 'https://huggingface.co/litert-community/gemma-4-E2B-it-litert-lm/resolve/main/gemma-4-E2B-it-web.task?download=true',
      filename: 'gemma-4-E2B-it-int4-Web.task',
      maxTokens: 4096,
      sizeMB: 1500,
    },
    'gemma4-e4b': {
      name: 'Gemma 4 E4B',
      description: 'より高品質な Gemma 4 モデル',
      url: 'https://huggingface.co/litert-community/gemma-4-E4B-it-litert-lm/resolve/main/gemma-4-E4B-it-int4-Web.task?download=true',
      filename: 'gemma-4-E4B-it-int4-Web.task',
      maxTokens: 4096,
      sizeMB: 3000,
    },
  };

  // OPFSのルートディレクトリ取得
  async function getDir() {
    return await navigator.storage.getDirectory();
  }

  // モデルがOPFSにキャッシュ済みか確認
  async function isModelCached(modelKey) {
    try {
      const dir = await getDir();
      const fileHandle = await dir.getFileHandle(MODEL_CONFIGS[modelKey].filename, { create: false });
      const file = await fileHandle.getFile();
      return file.size > 0;
    } catch {
      return false;
    }
  }

  // OPFS上のモデルファイル情報を取得
  async function getModelInfo(modelKey) {
    try {
      const dir = await getDir();
      const fileHandle = await dir.getFileHandle(MODEL_CONFIGS[modelKey].filename, { create: false });
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
    const dir = await getDir();
    const fileHandle = await dir.getFileHandle(MODEL_CONFIGS[modelKey].filename);
    const file = await fileHandle.getFile();
    return file.stream().getReader();
  }

  // モデルをダウンロードしてOPFSに保存
  // onProgress(loaded, total, percent) を呼び続ける
  async function downloadModel(modelKey, onProgress, signal) {
    const config = MODEL_CONFIGS[modelKey];
    const response = await fetch(config.url, { signal });
    if (!response.ok) throw new Error(`HTTP ${response.status}`);

    const total = parseInt(response.headers.get('content-length') || '0', 10);
    const reader = response.body.getReader();

    const dir = await getDir();
    const fileHandle = await dir.getFileHandle(config.filename, { create: true });
    const writable = await fileHandle.createWritable();

    let loaded = 0;
    while (true) {
      const { done, value } = await reader.read();
      if (done) break;
      await writable.write(value);
      loaded += value.byteLength;
      const percent = total ? Math.round((loaded / total) * 100) : 0;
      onProgress?.(loaded, total, percent);
    }
    await writable.close();
  }

  // OPFSからモデルを削除
  async function deleteModel(modelKey) {
    try {
      const dir = await getDir();
      await dir.removeEntry(MODEL_CONFIGS[modelKey].filename);
    } catch { }
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
    deleteModel,
    getStorageUsage,
  };
})();
