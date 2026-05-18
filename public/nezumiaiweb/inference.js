'use strict';

// ===== inference.js =====
// OPFS から ReadableStreamDefaultReader でモデルを渡す

window.inference = (() => {
  let llmInference = null;
  let isLoaded = false;
  let currentModel = null;
  let _isStopped = false;

  let _bubble = null;
  let _startTime = 0;
  let _tokenCount = 0;

  async function load(modelKey, onStatus) {
    if (isLoaded && currentModel === modelKey) return;

    onStatus?.('WASMランタイムを初期化中...');

    const { FilesetResolver, LlmInference } = await import(
      'https://cdn.jsdelivr.net/npm/@mediapipe/tasks-genai@latest/genai_bundle.mjs'
    ).catch(() => {
      if (window.FilesetResolver && window.LlmInference) {
        return { FilesetResolver: window.FilesetResolver, LlmInference: window.LlmInference };
      }
      throw new Error('MediaPipe tasks-genai のロードに失敗しました');
    });

    onStatus?.('GenAIランタイムを準備中...');

    const genai = await FilesetResolver.forGenAiTasks(
      'https://cdn.jsdelivr.net/npm/@mediapipe/tasks-genai@latest/wasm'
    );

    const backend = window.state?.backend || localStorage.getItem('nezumi_backend') || 'webgpu';
    onStatus?.(`バックエンド: ${backend}`);
    onStatus?.('OPFSからモデルを読み込み中...');

    const modelReader = await modelManager.getModelReader(modelKey);
    const config = modelManager.MODEL_CONFIGS[modelKey];

    llmInference = await LlmInference.createFromOptions(genai, {
      baseOptions: {
        modelAssetBuffer: modelReader,
      },
      maxTokens: config.maxTokens,
      topK: 40,
      temperature: 0.8,
      randomSeed: 101,
    });

    isLoaded = true;
    currentModel = modelKey;
    onStatus?.('準備完了');
  }

  async function generate(prompt, callbacks = {}) {
    const modelKey = window.state?.model || 'gemma4-e2b';
    const thinking = window.state?.thinking || false;

    if (!isLoaded || currentModel !== modelKey) {
      const overlay = document.getElementById('loadOverlay');
      const status = document.getElementById('loadStatus');
      const bar = document.getElementById('loadBar');

      overlay?.classList.remove('hidden');
      if (bar) bar.style.width = '20%';

      await load(modelKey, (msg) => {
        if (status) status.textContent = msg;
      });

      if (bar) bar.style.width = '100%';
      setTimeout(() => overlay?.classList.add('hidden'), 400);
    }

    _isStopped = false;
    callbacks.onStart?.();

    let fullText = '';
    let thinkText = '';
    let isDone = false;

    const STOP_TOKEN = '<turn|>';

    try {
      const promptText = thinking
        ? buildPromptWithThinking(prompt)
        : buildPrompt(prompt);



      await llmInference.generateResponse(
        promptText,
        (partial, done) => {
          if (_isStopped || isDone) return;

          fullText += partial;

          // ストップトークンが出たら以降を切り捨てて終了
          const stopIdx = fullText.indexOf(STOP_TOKEN);
          if (stopIdx !== -1) {
            fullText = fullText.slice(0, stopIdx);
            done = true;
          }

          // <|channel>thought...<channel|> を除いた表示用テキスト
          const thinkMatch = fullText.match(/<\|channel>thought([\s\S]*?)(?:<channel\|>|$)/i);
          const currentThink = thinkMatch ? thinkMatch[1] : '';
          const displayText = fullText
            .replace(/<\|channel>thought[\s\S]*?<channel\|>/gi, '')
            .replace(/<\|channel>thought[\s\S]*/gi, '')
            .trim();

          if (!done) {
            callbacks.onToken?.(partial, displayText, currentThink);
            return;
          }

          isDone = true;
          callbacks.onToken?.(partial, displayText, currentThink);

          thinkText = currentThink;
          const cleaned = fullText
            .replace(/<\|channel>thought[\s\S]*?<channel\|>/gi, '')
            .replace(/<\|channel>thought[\s\S]*/gi, '')
            .trim();
          callbacks.onDone?.(cleaned, thinkText);
        }
      );
    } catch (e) {
      if (!_isStopped) callbacks.onError?.(e);
    }
  }

  function stop() {
    _isStopped = true;
    try {
      llmInference?.close();
      isLoaded = false; // 次回再ロードが必要
    } catch { }
  }

  function buildPrompt(userText) {
    const allMessages = (window.state?.messages || []);
    const history = allMessages.slice(-11, -1);
    const sys = window.state?.systemPrompt;
    let prompt = sys ? `<|turn>user\n${sys}<turn|>\n<|turn>model\nはい<turn|>\n` : '';
    history.forEach(m => {
      if (m.role === 'user') {
        prompt += `<|turn>user\n${m.text}<turn|>\n`;
      } else if (m.role === 'ai' || m.role === 'model') {
        prompt += `<|turn>model\n${m.text}<turn|>\n`;
      }
    });
    prompt += `<|turn>user\n${userText}<turn|>\n<|turn>model\n`;
    return prompt;
  }

  function buildPromptWithThinking(userText) {
    const allMessages = (window.state?.messages || []);
    const history = allMessages.slice(-11, -1);
    const sys = window.state?.systemPrompt;
    let prompt = `<|turn>user\n<|think|><turn|>\n`;
    if (sys) prompt += `<|turn>user\n${sys}<turn|>\n<|turn>model\nはい<turn|>\n`;
    history.forEach(m => {
      if (m.role === 'user') {
        prompt += `<|turn>user\n${m.text}<turn|>\n`;
      } else if (m.role === 'ai' || m.role === 'model') {
        prompt += `<|turn>model\n${m.text}<turn|>\n`;
      }
    });
    prompt += `<|turn>user\n${userText}<turn|>\n<|turn>model\n`;
    return prompt;
  }

  return {
    load,
    generate,
    stop,
    get _bubble() { return _bubble; },
    set _bubble(v) { _bubble = v; },
    get _startTime() { return _startTime; },
    set _startTime(v) { _startTime = v; },
    get _tokenCount() { return _tokenCount; },
    set _tokenCount(v) { _tokenCount = v; },
  };
})();
