let Module;
let engineReady = false;

function post(type, payload = {}, transfer = []) {
  self.postMessage({ type, ...payload }, transfer);
}

function writeModelFiles(files) {
  Module.FS.mkdir('/model');
  for (const file of files) {
    const bytes = new Uint8Array(file.bytes);
    Module.FS.writeFile(`/model/${file.name}`, bytes);
  }
}

self.onmessage = async (event) => {
  const message = event.data || {};
  try {
    if (message.type === 'init') {
      const factory = (await import('./wasm/mnn_sd_wasm.js')).default;
      Module = await factory();
      Module.create();
      post('ready');
      return;
    }
    if (!Module) throw new Error('WASM が初期化されていません');
    if (message.type === 'load') {
      writeModelFiles(message.files || []);
      const ok = Module.load('/model', message.backend === 'opencl' ? 1 : 0);
      if (!ok) throw new Error(Module.lastError());
      post('loaded', { capabilities: Module.capabilities() });
      return;
    }
    if (message.type === 'generate') {
      post('status', { message: '生成中...' });
      const result = Module.generate(
        message.prompt || '', message.negativePrompt || '',
        message.width || 256, message.height || 256,
        message.steps || 20, message.cfg || 7.5,
        message.seed ?? -1, message.scheduler || 0
      );
      if (!result) throw new Error(Module.lastError());
      const pixels = new Uint8Array(result.data);
      post('result', {
        width: result.width,
        height: result.height,
        pixels: pixels.buffer,
      }, [pixels.buffer]);
      return;
    }
    if (message.type === 'destroy') {
      Module.destroy();
      engineReady = false;
    }
  } catch (error) {
    post('error', { message: error?.message || String(error) });
  }
};
