'use strict';

/* ------------------------------------------------------------------ *
 * DoNotTryAccessibility - KernelSU / APatch module WebUI
 * ------------------------------------------------------------------ */

const CFG_KEY = 'dnta_config';
const STATUS_KEY = 'dnta_status';

let config = null;
let packages = [];
let status = null;
let filter = 'all';
let query = '';
let saveTimer = null;
let saving = false;
let callbackSeq = 0;

const $ = (id) => document.getElementById(id);

/* ----------------------------- shell ----------------------------- */

function exec(command) {
  return new Promise((resolve) => {
    const bridge = window.ksu;
    if (!bridge || typeof bridge.exec !== 'function') {
      resolve({ errno: -1, stdout: '', stderr: 'WebUI bridge not available' });
      return;
    }
    const name = '__dnta_exec_' + (++callbackSeq) + '_' + Date.now();
    let finished = false;

    const finish = (errno, stdout, stderr) => {
      if (finished) return;
      finished = true;
      clearTimeout(timer);
      delete window[name];
      resolve({
        errno: typeof errno === 'number' ? errno : 0,
        stdout: stdout == null ? '' : String(stdout),
        stderr: stderr == null ? '' : String(stderr)
      });
    };

    const timer = setTimeout(() => finish(-2, '', 'timeout'), 20000);
    window[name] = finish;

    try {
      bridge.exec(command, '{}', name);
    } catch (e) {
      try {
        bridge.exec(command, name);
      } catch (e2) {
        finish(-3, '', String(e2 || e));
      }
    }
  });
}

/* ----------------------------- config ---------------------------- */

function defaults() {
  return {
    enabled: true,
    hideAccessibility: true,
    hideAdb: true,
    autoFill: false,
    autoUsers: {},
    block: [],
    auto: [],
    allow: []
  };
}

function normalize(raw) {
  const c = raw && typeof raw === 'object' ? raw : {};
  return {
    enabled: c.enabled !== false,
    hideAccessibility: c.hideAccessibility !== false,
    hideAdb: c.hideAdb !== false,
    autoFill: c.autoFill === true,
    autoUsers: (c.autoUsers && typeof c.autoUsers === 'object' && !Array.isArray(c.autoUsers)) ? c.autoUsers : {},
    block: Array.isArray(c.block) ? c.block.slice() : [],
    auto: Array.isArray(c.auto) ? c.auto.slice() : [],
    allow: Array.isArray(c.allow) ? c.allow.slice() : []
  };
}

async function readConfig() {
  const r = await exec('settings get global ' + CFG_KEY);
  const raw = (r.stdout || '').trim();
  if (!raw || raw === 'null') return defaults();
  try {
    return normalize(JSON.parse(raw));
  } catch (e) {
    toast('配置解析失败，已使用默认值');
    return defaults();
  }
}

async function writeConfig() {
  if (!config) return false;
  const json = JSON.stringify(config);
  // Package names never contain single quotes, so wrapping the JSON in single
  // quotes is safe and avoids shell interpolation.
  const r = await exec("settings put global " + CFG_KEY + " '" + json + "'");
  if (r.errno !== 0) {
    toast('保存失败：' + (r.stderr || r.errno));
    return false;
  }
  return true;
}

function scheduleSave() {
  if (saveTimer) clearTimeout(saveTimer);
  saveTimer = setTimeout(async () => {
    if (saving) {
      scheduleSave();
      return;
    }
    saving = true;
    try {
      if (await writeConfig()) toast('已保存');
    } finally {
      saving = false;
      renderAutoHint();
      updateStatusPill();
    }
  }, 350);
}

function addUnique(list, value) {
  if (!list.includes(value)) list.push(value);
}

function removeValue(list, value) {
  const i = list.indexOf(value);
  if (i >= 0) list.splice(i, 1);
}

function isBlocked(pkg) {
  if (!config) return false;
  if (config.allow.includes(pkg)) return false;
  return config.block.includes(pkg) || config.auto.includes(pkg);
}

/* ----------------------------- status ---------------------------- */

function updateStatusPill() {
  const pill = $('status-pill');
  if (status && status.active) {
    pill.textContent = 'Zygisk 已生效';
    pill.className = 'pill pill-on';
  } else {
    pill.textContent = '未生效';
    pill.className = 'pill pill-off';
  }
}

async function readStatus() {
  const [s, b] = await Promise.all([
    exec('settings get global ' + STATUS_KEY),
    exec('settings get global boot_count')
  ]);
  const raw = (s.stdout || '').trim();
  const boot = parseInt((b.stdout || '').trim(), 10);
  if (!raw || raw === 'null') {
    status = { active: false, version: '', detail: '未找到心跳，模块可能未安装或未重启' };
    return;
  }
  try {
    const json = JSON.parse(raw);
    const bootId = (await exec('cat /proc/sys/kernel/random/boot_id')).stdout.trim();
    const active = json.bootId
      ? json.bootId === bootId
      : Number(json.boot) === boot;
    status = {
      active,
      version: json.version || '?',
      detail: active
        ? 'v' + (json.version || '?') + ' · boot ' + boot
        : '心跳来自上一次开机，模块当前未生效'
    };
  } catch (e) {
    status = { active: false, version: '', detail: '心跳解析失败' };
  }
}

function renderStatus() {
  const title = $('status-title');
  const detail = $('status-detail');
  const card = $('status-card');
  if (!status) return;
  if (status.active) {
    title.textContent = 'Zygisk 钩子已生效';
    detail.textContent = status.detail;
    card.style.borderColor = 'var(--accent)';
  } else {
    title.textContent = 'Zygisk 钩子未生效';
    detail.textContent = status.detail;
    card.style.borderColor = 'var(--warn)';
  }
  updateStatusPill();
}

/* ---------------------------- packages --------------------------- */

function parseList(raw) {
  let value = raw;
  if (typeof value === 'string') {
    try { value = JSON.parse(value); } catch (e) { value = []; }
  }
  if (!Array.isArray(value)) return [];
  return value.map((item) => {
    if (typeof item === 'string') return { packageName: item.replace(/^package:/, '') };
    if (item && typeof item === 'object') return item;
    return null;
  }).filter(Boolean);
}

async function commandSet(command) {
  const r = await exec(command);
  const set = new Set();
  (r.stdout || '').split('\n').forEach((line) => {
    const value = line.trim().replace(/^package:/, '');
    if (value) set.add(value);
  });
  return set;
}

async function loadPackages() {
  // pm list packages works on every manager and returns all packages,
  // including system apps.  ksu.listPackages is only a fallback.
  let rawList = [];
  const shellList = await exec('pm list packages');
  rawList = parseList(shellList.stdout);

  if (!rawList.length) {
    try {
      if (window.ksu && typeof window.ksu.listPackages === 'function') {
        rawList = parseList(window.ksu.listPackages('all'));
      }
    } catch (e) { /* ignore */ }
  }

  const userSet = await commandSet('pm list packages -3');
  const systemSet = await commandSet('pm list packages -s');

  let infoMap = {};
  try {
    if (window.ksu && typeof window.ksu.getPackagesInfo === 'function') {
      let info;
      try {
        info = window.ksu.getPackagesInfo(JSON.stringify(rawList.map((p) => p.packageName)));
      } catch (e) {
        info = window.ksu.getPackagesInfo(JSON.stringify(rawList.map((p) => p.packageName)));
      }
      const parsed = parseList(info);
      parsed.forEach((p) => {
        if (p.packageName) infoMap[p.packageName] = p;
      });
    }
  } catch (e) { /* labels stay empty */ }

  const seen = new Set();
  const result = [];
  rawList.forEach((item) => {
    const pkg = item.packageName;
    if (!pkg || seen.has(pkg)) return;
    seen.add(pkg);
    const info = infoMap[pkg] || item;
    let icon = info.icon || info.appIcon || '';
    if (icon && !String(icon).startsWith('data:')) {
      icon = 'data:image/png;base64,' + icon;
    }
    result.push({
      packageName: pkg,
      label: info.label || info.appLabel || pkg,
      icon: icon || '',
      system: systemSet.has(pkg) || (!userSet.has(pkg) && !systemSet.has(pkg) && !!info.system)
    });
  });

  result.sort((a, b) => a.label.localeCompare(b.label, 'zh-CN'));

  packages = result;
}

/* ----------------------------- render ---------------------------- */

function visiblePackages() {
  const q = query.toLowerCase();
  return packages.filter((p) => {
    if (filter === 'blocked' && !isBlocked(p.packageName)) return false;
    if (filter === 'user' && p.system) return false;
    if (filter === 'system' && !p.system) return false;
    if (!q) return true;
    return p.label.toLowerCase().includes(q) ||
      p.packageName.toLowerCase().includes(q);
  });
}

function iconNode(pkg) {
  const entry = packages.find((p) => p.packageName === pkg);
  if (entry && entry.icon) {
    const img = document.createElement('img');
    img.className = 'app-icon';
    img.src = entry.icon;
    img.alt = '';
    img.loading = 'lazy';
    return img;
  }
  const div = document.createElement('div');
  div.className = 'app-icon placeholder';
  div.textContent = (pkg.charAt(0) || '?').toUpperCase();
  return div;
}

function renderList() {
  const list = $('package-list');
  const empty = $('list-empty');
  const loading = $('list-loading');
  const items = visiblePackages().sort((a, b) => {
    // Checked apps stay pinned to the top so the unchecked ones are easy to find.
    const ab = isBlocked(a.packageName) ? 1 : 0;
    const bb = isBlocked(b.packageName) ? 1 : 0;
    if (ab !== bb) return bb - ab;
    return a.label.localeCompare(b.label, 'zh-CN');
  });

  loading.hidden = true;
  list.innerHTML = '';
  empty.hidden = items.length !== 0;
  $('list-count').textContent = items.length + ' / ' + packages.length;

  const fragment = document.createDocumentFragment();
  items.forEach((p) => {
    const li = document.createElement('li');
    li.dataset.pkg = p.packageName;

    li.appendChild(iconNode(p.packageName));

    const text = document.createElement('div');
    text.className = 'app-text';

    const label = document.createElement('div');
    label.className = 'app-label';
    label.textContent = p.label;

    if (config.auto.includes(p.packageName) && !config.allow.includes(p.packageName)) {
      const badge = document.createElement('span');
      badge.className = 'badge';
      badge.textContent = '自动';
      label.appendChild(badge);
    }

    const pkg = document.createElement('div');
    pkg.className = 'app-pkg';
    pkg.textContent = p.packageName;

    text.appendChild(label);
    text.appendChild(pkg);
    li.appendChild(text);

    const check = document.createElement('input');
    check.type = 'checkbox';
    check.checked = isBlocked(p.packageName);
    li.appendChild(check);

    fragment.appendChild(li);
  });
  list.appendChild(fragment);
}

function togglePackage(pkg) {
  const nowBlocked = isBlocked(pkg);
  if (nowBlocked) {
    removeValue(config.block, pkg);
    removeValue(config.auto, pkg);
    addUnique(config.allow, pkg);
  } else {
    addUnique(config.block, pkg);
    removeValue(config.auto, pkg);
    removeValue(config.allow, pkg);
  }
  renderList();
  renderAutoHint();
  scheduleSave();
}

function renderAutoHint() {
  if (!config) return;
  const auto = config.auto.filter((p) => !config.allow.includes(p));
  const baselineUsers = Object.keys(config.autoUsers || {}).length;
  $('auto-hint').textContent = config.autoFill
    ? (baselineUsers > 0
      ? '自动填充已开启：已为 ' + baselineUsers + ' 个用户记录基线。仅新安装的用户应用会自动加入；同一次扫描出现多个新装时只加入最新安装的那个。'
      : '自动填充已开启，等待钩子记录基线…')
    : '自动填充已关闭。已加入的应用仍保持拦截，可手动取消；重新开启会重新记录基线。';
  $('auto-list').textContent = auto.length
    ? auto.join('、')
    : '暂无自动填充的应用。新安装的用户应用会由系统安装回调自动加入。';
}

/* ----------------------------- events --------------------------- */

function bind() {
  $('sw-enabled').addEventListener('change', (e) => {
    config.enabled = e.target.checked;
    scheduleSave();
  });
  $('sw-accessibility').addEventListener('change', (e) => {
    config.hideAccessibility = e.target.checked;
    scheduleSave();
  });
  $('sw-adb').addEventListener('change', (e) => {
    config.hideAdb = e.target.checked;
    scheduleSave();
  });
  $('sw-auto').addEventListener('change', (e) => {
    config.autoFill = e.target.checked;
    // A fresh enable records a new baseline; disabling also forgets it so that
    // apps installed while the feature was off are never added in bulk.
    config.autoUsers = {};
    renderAutoHint();
    scheduleSave();
  });

  $('search').addEventListener('input', (e) => {
    query = e.target.value.trim();
    renderList();
  });

  $('chips').addEventListener('click', (e) => {
    const chip = e.target.closest('.chip');
    if (!chip) return;
    filter = chip.dataset.filter;
    document.querySelectorAll('.chip').forEach((c) => c.classList.toggle('active', c === chip));
    renderList();
  });

  $('package-list').addEventListener('click', (e) => {
    const li = e.target.closest('li[data-pkg]');
    if (!li) return;
    togglePackage(li.dataset.pkg);
  });

  $('btn-clear-auto').addEventListener('click', () => {
    config.auto.forEach((pkg) => addUnique(config.allow, pkg));
    config.auto = [];
    renderList();
    renderAutoHint();
    scheduleSave();
  });

  document.addEventListener('visibilitychange', () => {
    if (!document.hidden) refresh();
  });
}

/* ------------------------------ toast --------------------------- */

let toastTimer = null;
function toast(message) {
  const node = $('toast');
  node.textContent = message;
  node.hidden = false;
  if (toastTimer) clearTimeout(toastTimer);
  toastTimer = setTimeout(() => { node.hidden = true; }, 2200);
}

/* ------------------------------ init ---------------------------- */

async function refresh() {
  await readStatus();
  renderStatus();

  config = await readConfig();
  $('sw-enabled').checked = config.enabled;
  $('sw-accessibility').checked = config.hideAccessibility;
  $('sw-adb').checked = config.hideAdb;
  $('sw-auto').checked = config.autoFill;
  renderAutoHint();

  await loadPackages();
  renderList();
}

async function init() {
  if (!window.ksu) {
    $('status-title').textContent = '当前管理器不支持模块 WebUI';
    $('status-detail').textContent =
      '请使用 KernelSU / APatch（或支持 webroot 的 WebUI 宿主）打开本模块。';
    $('status-pill').textContent = '不支持';
    $('status-pill').className = 'pill pill-off';
    $('list-loading').textContent = '无法读取应用列表';
    return;
  }
  bind();
  try {
    await refresh();
  } catch (e) {
    toast('初始化失败：' + e);
  }
}

init();
