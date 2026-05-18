'use strict';

// 依存関係チェック
if (!window.modelManager) throw new Error('model.js が読み込まれていません');
if (!window.inference) throw new Error('inference.js が読み込まれていません');

// ===== 定数 =====
const NEZUMI_ICON_SVG = 'nezumi-icon.svg';
const MAX_TOKENS = 4096;
const STORAGE_KEY_HISTORY = 'nezumi_history';
const STORAGE_KEY_THEME = 'nezumi_theme';
const STORAGE_KEY_MODEL = 'nezumi_model';
const STORAGE_KEY_SECRET = 'nezumi_secret';
const STORAGE_KEY_BACKEND = 'nezumi_backend';
const STORAGE_KEY_TEMP = 'nezumi_temperature';
const STORAGE_KEY_TOPK = 'nezumi_topk';
const STORAGE_KEY_SYSTEM_PROMPT = 'nezumi_system_prompt';
const STORAGE_KEY_ANDROID_RECOMMEND = 'nezumi_android_recommend_dismissed';

// ===== 状態 =====
const state = {
  messages: [],
  history: [],
  currentSessionId: null,
  contextUsed: 0,
  isGenerating: false,
  model: 'gemma4-e2b',
  thinking: false,
  theme: 'dark',
  secret: false,
  backend: 'webgpu',
  temperature: 0.8,
  topK: 40,
  systemPrompt: '',
};
window.state = state;

// ===== DOM =====
const $ = id => document.getElementById(id);
const dom = {
  app: $('app'),
  menuBtn: $('menuBtn'),
  drawer: $('drawer'),
  drawerOverlay: $('drawerOverlay'),
  newSessionBtn: $('newSessionBtn'),
  chatArea: $('chatArea'),
  emptyState: $('emptyState'),
  inputField: $('inputField'),
  sendBtn: $('sendBtn'),
  modelSelector: $('modelSelector'),
  thinkingToggle: $('thinkingToggle'),
  themeBtn: $('themeBtn'),
  contextUsed: $('contextUsed'),
  contextMax: $('contextMax'),
  contextBarFill: $('contextBarFill'),
  historyList: $('historyList'),
  loadOverlay: $('loadOverlay'),
  loadBar: $('loadBar'),
  loadStatus: $('loadStatus'),
  androidRecommend: $('androidRecommend'),
  androidRecommendClose: $('androidRecommendClose'),
};

let modelManagerDownloadController = null;
let modelManagerDownloadingKey = null;
let openHistoryMenuId = null;

// ===== テーマ =====
function applyTheme(theme) {
  state.theme = theme;
  document.body.classList.toggle('dark', theme === 'dark');
  localStorage.setItem(STORAGE_KEY_THEME, theme);
}

dom.themeBtn.addEventListener('click', () => {
  applyTheme(state.theme === 'dark' ? 'light' : 'dark');
});

// ===== Android版おすすめ =====
function isAndroid() {
  const ua = navigator.userAgent || navigator.vendor || '';
  const uaData = navigator.userAgentData;
  return /Android/i.test(ua) || uaData?.platform === 'Android';
}

function initAndroidRecommend() {
  if (!dom.androidRecommend || !isAndroid()) return;
  if (localStorage.getItem(STORAGE_KEY_ANDROID_RECOMMEND) === '1') return;

  dom.androidRecommend.classList.remove('hidden');
  dom.androidRecommendClose?.addEventListener('click', () => {
    localStorage.setItem(STORAGE_KEY_ANDROID_RECOMMEND, '1');
    dom.androidRecommend.classList.add('hidden');
  });
}

// ===== ドロワー =====
function openDrawer() {
  dom.drawer.classList.add('open');
  dom.drawerOverlay.classList.add('open');
}
function closeDrawer() {
  dom.drawer.classList.remove('open');
  dom.drawerOverlay.classList.remove('open');
}
dom.menuBtn.addEventListener('click', openDrawer);
dom.drawerOverlay.addEventListener('click', closeDrawer);
dom.newSessionBtn.addEventListener('click', () => { newSession(); closeDrawer(); });
document.addEventListener('click', e => {
  if (!e.target.closest('.history-menu-wrap')) {
    closeHistoryMenus();
  }
});

// ===== 設定モーダル =====
const settingsOverlay = $('settingsOverlay');
const settingsClose = $('settingsClose');
const settingsThemeToggle = $('settingsThemeToggle');
const settingsSecretToggle = $('settingsSecretToggle');
const settingsTemp = $('settingsTemp');
const settingsTempVal = $('settingsTempVal');
const settingsTopK = $('settingsTopK');
const settingsTopKVal = $('settingsTopKVal');
const settingsSystemPrompt = $('settingsSystemPrompt');
const clearHistoryBtn = $('clearHistoryBtn');
const manageModelsBtn = $('manageModelsBtn');
const modelOverlay = $('modelOverlay');
const modelClose = $('modelClose');
const modelManagerCurrent = $('modelManagerCurrent');
const modelManagerUsage = $('modelManagerUsage');
const modelManagerList = $('modelManagerList');
const modelManagerProgress = $('modelManagerProgress');
const modelManagerProgressBar = $('modelManagerProgressBar');
const modelManagerProgressText = $('modelManagerProgressText');

function openSettings() {
  // 現在の値をUIに反映
  settingsThemeToggle.checked = state.theme === 'dark';
  settingsSecretToggle.checked = state.secret;
  settingsTemp.value = Math.round(state.temperature * 100);
  settingsTempVal.textContent = state.temperature.toFixed(1);
  settingsTopK.value = state.topK;
  settingsTopKVal.textContent = state.topK;
  settingsSystemPrompt.value = state.systemPrompt;
  settingsOverlay.classList.remove('hidden');
  closeDrawer();
}
function closeSettings() {
  settingsOverlay.classList.add('hidden');
}

$('settingsBtn').addEventListener('click', openSettings);
settingsClose.addEventListener('click', closeSettings);
settingsOverlay.addEventListener('click', e => {
  if (e.target === settingsOverlay) closeSettings();
});

settingsThemeToggle.addEventListener('change', () => {
  applyTheme(settingsThemeToggle.checked ? 'dark' : 'light');
});

settingsSecretToggle.addEventListener('change', () => {
  state.secret = settingsSecretToggle.checked;
  localStorage.setItem(STORAGE_KEY_SECRET, state.secret ? '1' : '0');
  // シークレットモード表示をトップバーに反映
  updateSecretIndicator();
});

settingsTemp.addEventListener('input', () => {
  state.temperature = settingsTemp.value / 100;
  settingsTempVal.textContent = state.temperature.toFixed(1);
  localStorage.setItem(STORAGE_KEY_TEMP, state.temperature);
});

settingsTopK.addEventListener('input', () => {
  state.topK = parseInt(settingsTopK.value);
  settingsTopKVal.textContent = state.topK;
  localStorage.setItem(STORAGE_KEY_TOPK, state.topK);
});

settingsSystemPrompt.addEventListener('input', () => {
  state.systemPrompt = settingsSystemPrompt.value;
  localStorage.setItem(STORAGE_KEY_SYSTEM_PROMPT, state.systemPrompt);
});

clearHistoryBtn.addEventListener('click', () => {
  if (!confirm('チャット履歴をすべて削除しますか？')) return;
  state.history = [];
  localStorage.removeItem(STORAGE_KEY_HISTORY);
  renderHistory();
  clearHistoryBtn.textContent = '削除済';
  setTimeout(() => clearHistoryBtn.textContent = '削除', 1500);
});

manageModelsBtn.addEventListener('click', () => {
  closeSettings();
  openModelManager();
});

// ===== モデル管理モーダル =====
function openModelManager() {
  modelOverlay.classList.remove('hidden');
  closeDrawer();
  renderModelManager();
}

function closeModelManager() {
  if (modelManagerDownloadingKey && !confirm('ダウンロード中です。閉じても処理は続きます。閉じますか？')) return;
  modelOverlay.classList.add('hidden');
}

$('modelBtn').addEventListener('click', openModelManager);
modelClose.addEventListener('click', closeModelManager);
modelOverlay.addEventListener('click', e => {
  if (e.target === modelOverlay) closeModelManager();
});

async function renderModelManager() {
  const configs = modelManager.MODEL_CONFIGS;
  modelManagerCurrent.textContent = configs[state.model]?.name || state.model;
  modelManagerList.innerHTML = '';

  const usage = await modelManager.getStorageUsage();
  modelManagerUsage.textContent = `OPFS ${formatBytes(usage)}`;

  for (const [key, cfg] of Object.entries(configs)) {
    const info = await modelManager.getModelInfo(key);
    const isActive = state.model === key;
    const card = document.createElement('div');
    card.className = 'model-card' + (isActive ? ' active' : '');

    const sizeText = info.cached ? formatBytes(info.size) : `目安 ${cfg.sizeMB.toLocaleString()} MB`;
    const updatedText = info.updatedAt ? ` / ${formatDateTime(info.updatedAt)}` : '';
    card.innerHTML = `
      <div class="model-card-header">
        <div>
          <div class="model-card-title">${cfg.name}</div>
          <div class="model-card-desc">${cfg.description}</div>
        </div>
        <span class="model-card-badge ${info.cached ? 'ready' : ''}">${info.cached ? '準備OK' : '未取得'}</span>
      </div>
      <div class="model-card-meta">${sizeText}${updatedText}</div>
      <div class="model-card-actions"></div>
    `;

    const actions = card.querySelector('.model-card-actions');

    const selectBtn = document.createElement('button');
    selectBtn.className = 'model-action-btn primary';
    selectBtn.textContent = isActive ? '使用中' : '使う';
    selectBtn.disabled = isActive || !info.cached || Boolean(modelManagerDownloadingKey);
    selectBtn.addEventListener('click', () => selectManagedModel(key));
    actions.appendChild(selectBtn);

    if (!info.cached) {
      const downloadBtn = document.createElement('button');
      downloadBtn.className = 'model-action-btn';
      downloadBtn.textContent = modelManagerDownloadingKey === key ? 'ダウンロード中' : 'ダウンロード';
      downloadBtn.disabled = Boolean(modelManagerDownloadingKey);
      downloadBtn.addEventListener('click', () => downloadManagedModel(key));
      actions.appendChild(downloadBtn);
    } else {
      const deleteBtn = document.createElement('button');
      deleteBtn.className = 'model-action-btn danger';
      deleteBtn.textContent = '削除';
      deleteBtn.disabled = Boolean(modelManagerDownloadingKey);
      deleteBtn.addEventListener('click', () => deleteManagedModel(key));
      actions.appendChild(deleteBtn);
    }

    if (modelManagerDownloadingKey === key) {
      const cancelBtn = document.createElement('button');
      cancelBtn.className = 'model-action-btn';
      cancelBtn.textContent = 'キャンセル';
      cancelBtn.addEventListener('click', () => modelManagerDownloadController?.abort());
      actions.appendChild(cancelBtn);
    }

    modelManagerList.appendChild(card);
  }

  await refreshModelSelectorOptions();
}

async function selectManagedModel(key) {
  if (!await modelManager.isModelCached(key)) return;
  state.model = key;
  dom.modelSelector.value = key;
  localStorage.setItem(STORAGE_KEY_MODEL, key);
  renderModelManager();
}

async function downloadManagedModel(key) {
  if (modelManagerDownloadingKey) return;
  modelManagerDownloadingKey = key;
  modelManagerDownloadController = new AbortController();
  modelManagerProgress.classList.remove('hidden');
  modelManagerProgressBar.style.width = '0%';
  modelManagerProgressText.textContent = 'ダウンロードを開始しています...';
  renderModelManager();

  try {
    await modelManager.downloadModel(key, (loaded, total, pct) => {
      modelManagerProgressBar.style.width = `${pct}%`;
      modelManagerProgressText.textContent = `${formatBytes(loaded)} / ${total ? formatBytes(total) : '--'}`;
    }, modelManagerDownloadController.signal);

    modelManagerProgressBar.style.width = '100%';
    modelManagerProgressText.textContent = 'ダウンロード完了';
    state.model = key;
    dom.modelSelector.value = key;
    localStorage.setItem(STORAGE_KEY_MODEL, key);
  } catch (e) {
    modelManagerProgressText.textContent = e.name === 'AbortError' ? 'キャンセルしました' : `エラー: ${e.message}`;
  } finally {
    modelManagerDownloadingKey = null;
    modelManagerDownloadController = null;
    renderModelManager();
  }
}

async function deleteManagedModel(key) {
  const cfg = modelManager.MODEL_CONFIGS[key];
  if (!confirm(`${cfg.name} を端末から削除しますか？`)) return;

  if (state.model === key) inference.stop();
  await modelManager.deleteModel(key);

  if (state.model === key) {
    const nextKey = await findFirstCachedModelKey();
    if (nextKey) {
      state.model = nextKey;
      dom.modelSelector.value = nextKey;
      localStorage.setItem(STORAGE_KEY_MODEL, nextKey);
    } else {
      alert('利用できるモデルがなくなりました。セットアップ画面でモデルを追加してください。');
      window.location.href = 'setup.html';
      return;
    }
  }

  renderModelManager();
}

async function refreshModelSelectorOptions() {
  const entries = await Promise.all(
    Object.keys(modelManager.MODEL_CONFIGS).map(async key => [key, await modelManager.isModelCached(key)])
  );
  const cachedMap = Object.fromEntries(entries);
  Array.from(dom.modelSelector.options).forEach(option => {
    option.disabled = !cachedMap[option.value];
  });
}

function updateSecretIndicator() {
  const existing = $('secretIndicator');
  if (state.secret) {
    if (!existing) {
      const el = document.createElement('div');
      el.id = 'secretIndicator';
      el.className = 'secret-indicator';
      el.textContent = '🔒 シークレット';
      document.querySelector('.context-bar-wrap').prepend(el);
    }
  } else {
    existing?.remove();
  }
}

// ===== モデル選択 =====
dom.modelSelector.addEventListener('change', async () => {
  const nextModel = dom.modelSelector.value;
  if (!await modelManager.isModelCached(nextModel)) {
    dom.modelSelector.value = state.model;
    openModelManager();
    return;
  }
  state.model = nextModel;
  localStorage.setItem(STORAGE_KEY_MODEL, state.model);
});

dom.thinkingToggle.addEventListener('change', () => {
  state.thinking = dom.thinkingToggle.checked;
});

// ===== コンテキストバー更新 =====
function updateContextBar(used) {
  state.contextUsed = used;
  dom.contextUsed.textContent = used;
  const pct = Math.min((used / MAX_TOKENS) * 100, 100);
  dom.contextBarFill.style.width = pct + '%';
  // 80%超えたら警告色
  dom.contextBarFill.style.background = pct > 80 ? '#ff6b6b' : 'var(--color-primary)';
}

// ===== メッセージ追加 =====
function addMessage(role, text, stats = null) {
  const msg = { role, text, time: new Date(), stats };
  state.messages.push(msg);

  hideEmptyState();

  const row = document.createElement('div');
  row.className = `message-row ${role}`;

  const bubble = document.createElement('div');
  bubble.className = 'bubble';
  bubble.textContent = text;
  row.appendChild(bubble);

  const meta = document.createElement('div');
  meta.className = 'message-meta';
  meta.textContent = formatTime(msg.time);
  row.appendChild(meta);

  // アクションボタン
  const actions = document.createElement('div');
  actions.className = 'message-actions';

  const copyBtn = document.createElement('button');
  copyBtn.className = 'msg-action-btn primary';
  copyBtn.textContent = 'コピー';
  copyBtn.addEventListener('click', () => {
    navigator.clipboard.writeText(text);
    copyBtn.textContent = 'コピー済';
    setTimeout(() => copyBtn.textContent = 'コピー', 1500);
  });
  actions.appendChild(copyBtn);

  if (role === 'user') {
    const cancelBtn = document.createElement('button');
    cancelBtn.className = 'msg-action-btn';
    cancelBtn.textContent = '取り消し';
    cancelBtn.addEventListener('click', () => removeLastUserMessage(row));
    actions.appendChild(cancelBtn);
  }

  if (role === 'ai' && stats) {
    const statsRow = document.createElement('div');
    statsRow.className = 'ai-stats';
    statsRow.innerHTML = `<span class="ai-stats-text">${stats.tps.toFixed(1)} t/s · 生成 ${stats.time.toFixed(1)}s</span>`;
    row.appendChild(statsRow);
  }

  row.appendChild(actions);
  dom.chatArea.appendChild(row);
  scrollToBottom();

  // 推定トークン更新（文字数/4で近似）
  const totalChars = state.messages.reduce((s, m) => s + m.text.length, 0);
  updateContextBar(Math.round(totalChars / 4));

  return row;
}

function removeLastUserMessage(row) {
  if (state.isGenerating) return;
  const idx = state.messages.findLastIndex(m => m.role === 'user');
  if (idx !== -1) state.messages.splice(idx, 1);
  row.remove();
  if (state.messages.length === 0) showEmptyState();
}

// ===== タイピングインジケーター =====
function showTyping() {
  const row = document.createElement('div');
  row.className = 'message-row ai typing-indicator';
  row.id = 'typingIndicator';
  const bubble = document.createElement('div');
  bubble.className = 'bubble';
  bubble.innerHTML = '<div class="typing-dot"></div><div class="typing-dot"></div><div class="typing-dot"></div>';
  row.appendChild(bubble);
  dom.chatArea.appendChild(row);
  scrollToBottom();
}
function hideTyping() {
  const el = $('typingIndicator');
  if (el) el.remove();
}

// ===== 簡易Markdownパーサー =====
function parseMarkdown(text) {
  // XSS対策: まずエスケープ
  const esc = text
    .replace(/&/g, '&amp;')
    .replace(/</g, '&lt;')
    .replace(/>/g, '&gt;');

  return esc
    // コードブロック ```...```
    .replace(/```([\s\S]*?)```/g, '<pre><code>$1</code></pre>')
    // インラインコード `...`
    .replace(/`([^`]+)`/g, '<code>$1</code>')
    // 太字 **...**
    .replace(/\*\*(.+?)\*\*/g, '<strong>$1</strong>')
    // 斜体 *...*
    .replace(/\*(.+?)\*/g, '<em>$1</em>')
    // 番号リスト
    .replace(/^\d+\.\s+(.+)$/gm, '<li>$1</li>')
    // 箇条書き - ...
    .replace(/^[\-\*]\s+(.+)$/gm, '<li>$1</li>')
    // liをulでラップ（連続するliをまとめる）
    .replace(/(<li>.*<\/li>\n?)+/g, m => `<ul>${m}</ul>`)
    // 改行
    .replace(/\n/g, '<br>');
}

// ===== ストリーミング応答表示 =====
function startStreamMessage() {
  hideTyping();
  const row = document.createElement('div');
  row.className = 'message-row ai';
  row.id = 'streamingMsg';

  // 思考中ボックス（ストリーミング中は開いた状態）
  const thinkBox = document.createElement('div');
  thinkBox.className = 'think-box open';
  thinkBox.id = 'streamingThinkBox';
  thinkBox.innerHTML = `
    <div class="think-header">✨ 思考中...</div>
    <div class="think-body"></div>
  `;
  thinkBox.style.display = 'none';
  row.appendChild(thinkBox);

  const bubble = document.createElement('div');
  bubble.className = 'bubble';
  bubble.id = 'streamingBubble';
  row.appendChild(bubble);

  dom.chatArea.appendChild(row);
  scrollToBottom();
  return bubble;
}

function appendStream(bubble, chunk) {
  // ストリーミング中はプレーンテキストで表示（高速化）
  bubble.textContent += chunk;
  scrollToBottom();
}

function finalizeStream(bubble, fullText, stats, thinkText) {
  const row = bubble.closest('.message-row');
  row.removeAttribute('id');
  bubble.removeAttribute('id');

  // 完了後にMarkdownレンダリング
  bubble.innerHTML = parseMarkdown(fullText);

  // 思考ボックス：完了後も開いたまま、折りたたみボタン付き
  const existingThinkBox = row.querySelector('#streamingThinkBox') || row.querySelector('.think-box');
  if (existingThinkBox) existingThinkBox.remove();

  if (thinkText) {
    const thinkBox = document.createElement('div');
    thinkBox.className = 'think-box';
    thinkBox.innerHTML = `
      <div class="think-header">
        <span>推論を見る</span><span class="think-arrow">▼</span>
      </div>
      <div class="think-body" style="display:none">${parseMarkdown(thinkText)}</div>
    `;
    thinkBox.querySelector('.think-header').addEventListener('click', function() {
      const body = thinkBox.querySelector('.think-body');
      const arrow = thinkBox.querySelector('.think-arrow');
      const open = body.style.display !== 'none';
      body.style.display = open ? 'none' : 'block';
      arrow.textContent = open ? '▼' : '▲';
      this.querySelector('span').textContent = open ? '推論を見る' : '推論を隐す';
    });
    row.insertBefore(thinkBox, bubble);
  }

  const meta = document.createElement('div');
  meta.className = 'message-meta';
  meta.textContent = formatTime(new Date());
  row.appendChild(meta);

  if (stats) {
    const statsRow = document.createElement('div');
    statsRow.className = 'ai-stats';
    statsRow.innerHTML = `<span class="ai-stats-text">${stats.tps.toFixed(1)} t/s · 生成 ${stats.time.toFixed(1)}s</span>`;
    row.appendChild(statsRow);
  }

  const actions = document.createElement('div');
  actions.className = 'message-actions';
  const copyBtn = document.createElement('button');
  copyBtn.className = 'msg-action-btn primary';
  copyBtn.textContent = 'コピー';
  copyBtn.addEventListener('click', () => {
    navigator.clipboard.writeText(fullText);
    copyBtn.textContent = 'コピー済';
    setTimeout(() => copyBtn.textContent = 'コピー', 1500);
  });
  actions.appendChild(copyBtn);
  row.appendChild(actions);

  state.messages.push({ role: 'ai', text: fullText, time: new Date(), stats });
  saveHistory();
}

// ===== 送信 =====
async function send() {
  const text = dom.inputField.value.trim();
  if (!text || state.isGenerating) return;

  dom.inputField.value = '';
  resizeTextarea();
  state.isGenerating = true;
  setSendBtnState(false);

  // UIにユーザーバブルを表示（state.messagesへの追加はinference.generate呼び出し後）
  hideEmptyState();
  const userRow = document.createElement('div');
  userRow.className = 'message-row user';
  const userBubble = document.createElement('div');
  userBubble.className = 'bubble';
  userBubble.textContent = text;
  userRow.appendChild(userBubble);
  const userMeta = document.createElement('div');
  userMeta.className = 'message-meta';
  userMeta.textContent = formatTime(new Date());
  userRow.appendChild(userMeta);
  dom.chatArea.appendChild(userRow);
  scrollToBottom();

  showTyping();

  // generate呼び出し前にstateへ追加（buildPromptが履歴として使う）
  state.messages.push({ role: 'user', text, time: new Date() });
  const totalCharsUser = state.messages.reduce((s, m) => s + m.text.length, 0);
  updateContextBar(Math.round(totalCharsUser / 4));

  try {
    await inference.generate(text, {
      onStart: () => {
        hideTyping();
        const bubble = startStreamMessage();
        inference._bubble = bubble;
        inference._startTime = Date.now();
        inference._tokenCount = 0;
      },
      onToken: (token, displayText, currentThink) => {
        inference._tokenCount++;
        const row = inference._bubble?.closest('.message-row');
        const thinkBox = row?.querySelector('#streamingThinkBox');
        if (thinkBox && currentThink !== undefined) {
          thinkBox.style.display = '';
          thinkBox.querySelector('.think-body').textContent = currentThink;
        }
        inference._bubble.textContent = displayText;
        scrollToBottom();
      },
      onDone: (fullText, thinkText) => {
        const elapsed = (Date.now() - inference._startTime) / 1000;
        const tps = inference._tokenCount / elapsed;
        finalizeStream(inference._bubble, fullText, { tps, time: elapsed }, thinkText);
        const totalChars = state.messages.reduce((s, m) => s + m.text.length, 0);
        updateContextBar(Math.round(totalChars / 4));
      },
      onError: (err) => {
        hideTyping();
        addMessage('ai', `エラー: ${err.message}`);
      },
    });
  } catch (e) {
    hideTyping();
    addMessage('ai', `エラー: ${e.message}`);
  } finally {
    state.isGenerating = false;
    setSendBtnState(true);
  }
}

dom.sendBtn.addEventListener('click', send);
dom.inputField.addEventListener('keydown', e => {
  if (e.key === 'Enter' && !e.shiftKey) { e.preventDefault(); send(); }
});
dom.inputField.addEventListener('input', resizeTextarea);

function resizeTextarea() {
  const el = dom.inputField;
  el.style.height = 'auto';
  el.style.height = Math.min(el.scrollHeight, 120) + 'px';
}

function setSendBtnState(enabled) {
  if (enabled) {
    // 送信ボタンに戻す
    dom.sendBtn.disabled = false;
    dom.sendBtn.title = '送信';
    dom.sendBtn.innerHTML = `<svg width="20" height="20" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2.5">
      <line x1="5" y1="12" x2="19" y2="12"/>
      <polyline points="12 5 19 12 12 19"/>
    </svg>`;
    dom.sendBtn.onclick = send;
  } else {
    // 停止ボタンに切り替え
    dom.sendBtn.disabled = false;
    dom.sendBtn.title = '停止';
    dom.sendBtn.innerHTML = `<svg width="18" height="18" viewBox="0 0 24 24" fill="currentColor">
      <rect x="4" y="4" width="16" height="16" rx="2"/>
    </svg>`;
    dom.sendBtn.onclick = () => {
      inference.stop();
      // 途中までのテキストを確定
      const bubble = inference._bubble;
      if (bubble) {
        const partial = bubble.textContent.trim();
        if (partial) {
          finalizeStream(bubble, partial, null);
        } else {
          bubble.closest('.message-row')?.remove();
        }
      }
      hideTyping();
      state.isGenerating = false;
      setSendBtnState(true);
    };
  }
}

// ===== セッション管理 =====
function newSession() {
  if (state.messages.length > 0) saveHistory();
  state.messages = [];
  state.currentSessionId = Date.now().toString();
  dom.chatArea.innerHTML = '';
  showEmptyState();
  updateContextBar(0);
  renderHistory();
}

function saveHistory() {
  if (state.messages.length === 0) return;
  if (state.secret) return; // シークレットモード時は保存しない
  const firstUser = state.messages.find(m => m.role === 'user');
  const autoTitle = firstUser ? firstUser.text.slice(0, 30) + (firstUser.text.length > 30 ? '...' : '') : '新しいチャット';
  const existing = state.history.find(h => h.id === state.currentSessionId);
  const session = {
    id: state.currentSessionId,
    title: existing?.customTitle || autoTitle,
    customTitle: existing?.customTitle || '',
    pinned: Boolean(existing?.pinned),
    messages: state.messages,
    time: new Date().toISOString(),
  };
  const idx = state.history.findIndex(h => h.id === state.currentSessionId);
  if (idx !== -1) state.history[idx] = session;
  else state.history.unshift(session);
  persistHistory();
  renderHistory();
}

function loadSession(session) {
  state.messages = session.messages;
  state.currentSessionId = session.id;
  dom.chatArea.innerHTML = '';
  hideEmptyState();
  session.messages.forEach(m => {
    const row = document.createElement('div');
    row.className = `message-row ${m.role}`;
    const bubble = document.createElement('div');
    bubble.className = 'bubble';
    bubble.textContent = m.text;
    row.appendChild(bubble);
    const meta = document.createElement('div');
    meta.className = 'message-meta';
    meta.textContent = formatTime(new Date(m.time));
    row.appendChild(meta);
    dom.chatArea.appendChild(row);
  });
  const totalChars = session.messages.reduce((s, m) => s + m.text.length, 0);
  updateContextBar(Math.round(totalChars / 4));
  scrollToBottom();
  renderHistory();
}

function renderHistory() {
  const now = new Date();
  const todayStr = now.toDateString();
  dom.historyList.innerHTML = '';
  sortHistory();
  state.history.forEach(session => {
    const item = document.createElement('div');
    item.className = 'history-item' + (session.id === state.currentSessionId ? ' active' : '');
    if (session.pinned) item.classList.add('pinned');
    const t = new Date(session.time);
    const timeStr = t.toDateString() === todayStr
      ? t.toTimeString().slice(0, 5)
      : `${t.getMonth() + 1}/${t.getDate()}`;

    const main = document.createElement('button');
    main.className = 'history-main';
    main.type = 'button';
    main.innerHTML = `
      <span class="history-title">${escapeHtml(session.title)}</span>
      <span class="history-meta">${session.pinned ? '<span class="history-pin">固定</span>' : ''}<span class="history-time">${timeStr}</span></span>
    `;
    main.addEventListener('click', () => { loadSession(session); closeDrawer(); });

    const menuWrap = document.createElement('div');
    menuWrap.className = 'history-menu-wrap';

    const menuBtn = document.createElement('button');
    menuBtn.className = 'history-menu-btn';
    menuBtn.type = 'button';
    menuBtn.setAttribute('aria-haspopup', 'menu');
    menuBtn.setAttribute('aria-expanded', openHistoryMenuId === session.id ? 'true' : 'false');
    menuBtn.setAttribute('aria-label', 'セッションメニュー');
    menuBtn.innerHTML = '<span></span><span></span><span></span>';
    menuBtn.addEventListener('click', e => {
      e.stopPropagation();
      openHistoryMenuId = openHistoryMenuId === session.id ? null : session.id;
      renderHistory();
    });

    const menu = document.createElement('div');
    menu.className = 'history-menu' + (openHistoryMenuId === session.id ? ' open' : '');
    menu.setAttribute('role', 'menu');

    menu.appendChild(createHistoryMenuItem(session.pinned ? '固定を解除' : '固定', () => toggleHistoryPinned(session.id)));
    menu.appendChild(createHistoryMenuItem('リネーム', () => renameHistorySession(session.id)));
    menu.appendChild(createHistoryMenuItem('削除', () => deleteHistorySession(session.id), 'danger'));

    menuWrap.appendChild(menuBtn);
    menuWrap.appendChild(menu);
    item.appendChild(main);
    item.appendChild(menuWrap);
    dom.historyList.appendChild(item);
  });
}

function createHistoryMenuItem(label, onClick, variant = '') {
  const btn = document.createElement('button');
  btn.className = 'history-menu-item' + (variant ? ` ${variant}` : '');
  btn.type = 'button';
  btn.setAttribute('role', 'menuitem');
  btn.textContent = label;
  btn.addEventListener('click', e => {
    e.stopPropagation();
    onClick();
  });
  return btn;
}

function toggleHistoryPinned(sessionId) {
  const session = state.history.find(h => h.id === sessionId);
  if (!session) return;
  session.pinned = !session.pinned;
  openHistoryMenuId = null;
  persistHistory();
  renderHistory();
}

function renameHistorySession(sessionId) {
  const session = state.history.find(h => h.id === sessionId);
  if (!session) return;
  const nextTitle = prompt('セッション名を入力', session.title);
  if (nextTitle === null) return;
  const trimmed = nextTitle.trim();
  if (!trimmed) return;
  session.title = trimmed.slice(0, 80);
  session.customTitle = session.title;
  openHistoryMenuId = null;
  persistHistory();
  renderHistory();
}

function deleteHistorySession(sessionId) {
  const session = state.history.find(h => h.id === sessionId);
  if (!session) return;
  if (!confirm(`「${session.title}」を削除しますか？`)) return;
  state.history = state.history.filter(h => h.id !== sessionId);
  if (state.currentSessionId === sessionId) {
    state.messages = [];
    state.currentSessionId = Date.now().toString();
    dom.chatArea.innerHTML = '';
    showEmptyState();
    updateContextBar(0);
  }
  openHistoryMenuId = null;
  persistHistory();
  renderHistory();
}

function closeHistoryMenus() {
  if (!openHistoryMenuId) return;
  openHistoryMenuId = null;
  renderHistory();
}

function sortHistory() {
  state.history.sort((a, b) => {
    if (Boolean(a.pinned) !== Boolean(b.pinned)) return a.pinned ? -1 : 1;
    return new Date(b.time) - new Date(a.time);
  });
}

function persistHistory() {
  sortHistory();
  const pinned = state.history.filter(h => h.pinned);
  const unpinned = state.history.filter(h => !h.pinned);
  state.history = pinned.concat(unpinned.slice(0, Math.max(0, 30 - pinned.length)));
  localStorage.setItem(STORAGE_KEY_HISTORY, JSON.stringify(state.history));
}

// ===== ユーティリティ =====
function scrollToBottom() {
  dom.chatArea.scrollTop = dom.chatArea.scrollHeight;
}
function showEmptyState() {
  if (!dom.emptyState.parentNode) dom.chatArea.appendChild(dom.emptyState);
  dom.emptyState.style.display = 'flex';
}
function hideEmptyState() {
  dom.emptyState.style.display = 'none';
}
function formatTime(date) {
  return date.toTimeString().slice(0, 5);
}
function escapeHtml(text) {
  return String(text)
    .replace(/&/g, '&amp;')
    .replace(/</g, '&lt;')
    .replace(/>/g, '&gt;')
    .replace(/"/g, '&quot;')
    .replace(/'/g, '&#39;');
}
function formatDateTime(date) {
  return `${date.getMonth() + 1}/${date.getDate()} ${formatTime(date)}`;
}
function formatBytes(bytes) {
  if (!bytes) return '0 MB';
  const units = ['B', 'KB', 'MB', 'GB'];
  let value = bytes;
  let unit = 0;
  while (value >= 1024 && unit < units.length - 1) {
    value /= 1024;
    unit++;
  }
  const digits = unit >= 2 ? 1 : 0;
  return `${value.toFixed(digits)} ${units[unit]}`;
}

async function findFirstCachedModelKey() {
  for (const key of Object.keys(modelManager.MODEL_CONFIGS)) {
    if (await modelManager.isModelCached(key)) return key;
  }
  return null;
}

// ===== 初期化 =====
async function init() {
  initAndroidRecommend();

  // モデル未取得なら setup.html へ
  const keys = Object.keys(modelManager.MODEL_CONFIGS);
  const cached = await Promise.all(keys.map(k => modelManager.isModelCached(k)));
  if (!cached.some(Boolean)) {
    window.location.href = 'setup.html';
    return;
  }

  const savedTheme = localStorage.getItem(STORAGE_KEY_THEME) || 'dark';
  applyTheme(savedTheme);

  const savedModel = localStorage.getItem(STORAGE_KEY_MODEL) || 'gemma4-e2b';
  state.model = cached[keys.indexOf(savedModel)] ? savedModel : keys[cached.findIndex(Boolean)];
  dom.modelSelector.value = savedModel;
  dom.modelSelector.value = state.model;
  localStorage.setItem(STORAGE_KEY_MODEL, state.model);
  await refreshModelSelectorOptions();

  state.backend = localStorage.getItem(STORAGE_KEY_BACKEND) || 'webgpu';
  state.secret = localStorage.getItem(STORAGE_KEY_SECRET) === '1';
  state.temperature = parseFloat(localStorage.getItem(STORAGE_KEY_TEMP) || '0.8');
  state.topK = parseInt(localStorage.getItem(STORAGE_KEY_TOPK) || '40');
  state.systemPrompt = localStorage.getItem(STORAGE_KEY_SYSTEM_PROMPT) || '';
  updateSecretIndicator();

  const savedHistory = localStorage.getItem(STORAGE_KEY_HISTORY);
  if (savedHistory) {
    state.history = JSON.parse(savedHistory).map(session => ({
      ...session,
      title: session.title || '新しいチャット',
      customTitle: session.customTitle || '',
      pinned: Boolean(session.pinned),
    }));
    persistHistory();
  }
  renderHistory();

  state.currentSessionId = Date.now().toString();
  showEmptyState();
}

init();
