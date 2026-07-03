// ============================================================
// KLink Layout Engine — 布局自定义系统
// 支持三种布局模式 / 导航排序 / 可见性切换 / 自定义 CSS 注入
// ============================================================

const LayoutEngine = (function() {
    'use strict';

    const STORAGE_KEY = 'klink_layout_v1';

    // ======================== 默认配置 ========================
    const DEFAULT_CONFIG = {
        mode: 'sidebar-left',       // 'sidebar-left' | 'tabs-top' | 'tabs-bottom'
        navOrder: ['server', 'rooms', 'mods', 'theme', 'settings'],
        navVisible: {
            server: true,
            rooms: true,
            mods: true,
            theme: true,
            settings: true
        },
        customCss: ''
    };

    const NAV_LABELS = {
        server:   '服务器',
        rooms:    '房间发现',
        mods:     '模组管理',
        theme:    '主题',
        settings: '设置'
    };

    const NAV_ICONS = {
        server:   '◇',
        rooms:    '◎',
        mods:     '◫',
        theme:    '◐',
        settings: '⚙'
    };

    // ======================== 内部状态 ========================
    let _config = null;
    let _styleEl = null;

    // ======================== 应用布局 ========================
    function applyLayout(config) {
        _config = deepMerge(DEFAULT_CONFIG, config);

        const body = document.body;

        // 移除旧 layout class
        body.classList.remove('layout-sidebar-left', 'layout-tabs-top', 'layout-tabs-bottom');
        body.classList.add('layout-' + _config.mode);

        // 导航顺序 & 可见性
        applyNav(_config.navOrder, _config.navVisible);

        // 自定义 CSS
        applyCustomCss(_config.customCss);

        return _config;
    }

    function applyNav(order, visible) {
        const navList = document.querySelector('.nav-list');
        if (!navList) return;

        // 收集所有现有 nav-item
        const items = {};
        navList.querySelectorAll('.nav-item').forEach(el => {
            items[el.dataset.tab] = el;
        });

        // 按 order 重排，跳过不可见项
        navList.innerHTML = '';
        order.forEach(tabId => {
            if (visible && visible[tabId] === false) return;
            const el = items[tabId];
            if (el) {
                // 确保数据和图标最新
                el.querySelector('.nav-label').textContent = NAV_LABELS[tabId] || tabId;
                const icon = el.querySelector('.nav-icon');
                if (icon) icon.textContent = NAV_ICONS[tabId] || '';
                navList.appendChild(el);
            }
        });
    }

    function applyCustomCss(css) {
        if (!_styleEl) {
            _styleEl = document.createElement('style');
            _styleEl.id = 'layout-custom-css';
            document.head.appendChild(_styleEl);
        }
        _styleEl.textContent = css || '';
    }

    // ======================== 导出导入 ========================
    function exportLayout() {
        const config = getCurrent();
        const json = JSON.stringify(config, null, 2);

        if (window.KLink && window.KLink.shareTheme) {
            window.KLink.shareTheme('[KLink Layout]\n' + json);
        } else if (window.KLink && window.KLink.exportTheme) {
            window.KLink.exportTheme(json);
        } else {
            const blob = new Blob([json], { type: 'application/json' });
            const url = URL.createObjectURL(blob);
            const a = document.createElement('a');
            a.href = url;
            a.download = 'klink-layout.json';
            document.body.appendChild(a);
            a.click();
            document.body.removeChild(a);
            URL.revokeObjectURL(url);
        }
        return json;
    }

    function importLayout(jsonStr) {
        let config;
        try {
            config = JSON.parse(jsonStr);
        } catch (e) {
            throw new Error('JSON 解析失败: ' + e.message);
        }
        if (!config.mode && !config.navOrder && !config.customCss) {
            throw new Error('无效的布局文件');
        }
        applyLayout(config);
        save();
        return config;
    }

    // ======================== 持久化 ========================
    function save() {
        try {
            localStorage.setItem(STORAGE_KEY, JSON.stringify(_config));
        } catch (e) {}
    }

    function load() {
        try {
            const raw = localStorage.getItem(STORAGE_KEY);
            if (raw) {
                const saved = JSON.parse(raw);
                if (saved && saved.mode) {
                    applyLayout(saved);
                    return true;
                }
            }
        } catch (e) {}
        return false;
    }

    function reset() {
        localStorage.removeItem(STORAGE_KEY);
        applyLayout(DEFAULT_CONFIG);
    }

    // ======================== 查询 ========================
    function getCurrent() {
        return JSON.parse(JSON.stringify(_config || DEFAULT_CONFIG));
    }

    function getMode() {
        return (_config && _config.mode) || DEFAULT_CONFIG.mode;
    }

    // ======================== UI 绑定 ========================
    function bindUI() {
        const $ = (sel) => document.querySelector(sel);
        const $$ = (sel) => document.querySelectorAll(sel);

        if (document.getElementById('layout-ui-bound')) return;
        const bound = document.createElement('meta');
        bound.id = 'layout-ui-bound';
        document.head.appendChild(bound);

        function refreshUI() {
            const cfg = getCurrent();

            // 模式选择
            $$('#layoutModeRow .option-btn').forEach(b => {
                b.classList.toggle('active', b.dataset.layoutMode === cfg.mode);
            });

            // 导航排序列表
            renderNavList(cfg);

            // 自定义 CSS
            $('#inputCustomCss').value = cfg.customCss || '';
        }

        function renderNavList(cfg) {
            const container = $('#navOrderList');
            if (!container) return;

            const order = cfg.navOrder || DEFAULT_CONFIG.navOrder;
            const visible = cfg.navVisible || DEFAULT_CONFIG.navVisible;

            container.innerHTML = order.map((tabId, idx) => {
                const isVis = visible[tabId] !== false;
                const label = NAV_LABELS[tabId] || tabId;
                const isFirst = idx === 0;
                const isLast = idx === order.length - 1;
                return `
                    <div class="nav-order-item" data-tab="${escAttr(tabId)}">
                        <span class="nav-order-grip">⠿</span>
                        <span class="nav-order-label">${escAttr(label)}</span>
                        <button class="nav-order-btn nav-order-up ${isFirst ? 'disabled' : ''}" data-action="up" data-tab="${escAttr(tabId)}" ${isFirst ? 'disabled' : ''}>▲</button>
                        <button class="nav-order-btn nav-order-down ${isLast ? 'disabled' : ''}" data-action="down" data-tab="${escAttr(tabId)}" ${isLast ? 'disabled' : ''}>▼</button>
                        <button class="nav-order-btn nav-order-eye ${isVis ? '' : 'off'}" data-action="toggle" data-tab="${escAttr(tabId)}">${isVis ? '👁' : '─'}</button>
                    </div>`;
            }).join('');

            // 绑定事件
            container.querySelectorAll('.nav-order-btn').forEach(btn => {
                btn.addEventListener('click', function() {
                    const action = this.dataset.action;
                    const tabId = this.dataset.tab;
                    const cfg = getCurrent();
                    const idx = cfg.navOrder.indexOf(tabId);
                    if (idx < 0) return;

                    if (action === 'up' && idx > 0) {
                        [cfg.navOrder[idx], cfg.navOrder[idx - 1]] = [cfg.navOrder[idx - 1], cfg.navOrder[idx]];
                    } else if (action === 'down' && idx < cfg.navOrder.length - 1) {
                        [cfg.navOrder[idx], cfg.navOrder[idx + 1]] = [cfg.navOrder[idx + 1], cfg.navOrder[idx]];
                    } else if (action === 'toggle') {
                        cfg.navVisible[tabId] = !(cfg.navVisible[tabId] !== false);
                    } else {
                        return;
                    }
                    applyLayout(cfg);
                    save();
                    refreshUI();
                });
            });
        }

        // — 布局模式选择 —
        $$('#layoutModeRow .option-btn').forEach(btn => {
            btn.addEventListener('click', function() {
                const mode = this.dataset.layoutMode;
                const cfg = getCurrent();
                cfg.mode = mode;
                applyLayout(cfg);
                save();
                refreshUI();
            });
        });

        // — 自定义 CSS —
        const inputCss = $('#inputCustomCss');
        if (inputCss) {
            let cssTimer = null;
            inputCss.addEventListener('input', function() {
                clearTimeout(cssTimer);
                cssTimer = setTimeout(() => {
                    const cfg = getCurrent();
                    cfg.customCss = this.value;
                    applyLayout(cfg);
                    save();
                }, 300);
            });
        }

        // — 导出布局 —
        const btnExport = $('#btnExportLayout');
        if (btnExport) {
            btnExport.addEventListener('click', function() {
                exportLayout();
            });
        }

        // — 导入布局 —
        const btnImport = $('#btnImportLayout');
        if (btnImport) {
            btnImport.addEventListener('click', function() {
                if (window.KLink && window.KLink.pickThemeFile) {
                    // 复用主题导入桥
                    const orig = window.onThemeFileLoaded;
                    window.onThemeFileLoaded = function(jsonStr) {
                        try {
                            const config = JSON.parse(jsonStr);
                            if (config.mode || config.navOrder) {
                                importLayout(jsonStr);
                                refreshUI();
                                if (window.KLink.showToast) window.KLink.showToast('布局导入成功');
                            } else if (orig) {
                                orig(jsonStr); // 不是布局文件，交给主题处理
                            }
                        } catch (e) {
                            if (window.KLink.showToast) window.KLink.showToast('导入失败');
                        }
                        window.onThemeFileLoaded = orig;
                    };
                    window.KLink.pickThemeFile();
                } else {
                    $('#inputImportLayoutFile').click();
                }
            });
        }

        // fallback 文件导入
        const fileInput = $('#inputImportLayoutFile');
        if (fileInput) {
            fileInput.addEventListener('change', function() {
                const file = this.files[0];
                if (!file) return;
                const reader = new FileReader();
                reader.onload = function(e) {
                    try {
                        importLayout(e.target.result);
                        refreshUI();
                    } catch (err) {
                        alert('导入失败: ' + err.message);
                    }
                };
                reader.readAsText(file);
            });
        }

        // — 粘贴导入 —
        const btnApplyPasted = $('#btnApplyPastedLayout');
        if (btnApplyPasted) {
            btnApplyPasted.addEventListener('click', function() {
                const jsonStr = $('#inputImportLayoutJson').value.trim();
                if (!jsonStr) return;
                try {
                    importLayout(jsonStr);
                    refreshUI();
                    this.textContent = '应用成功';
                    setTimeout(() => { this.textContent = '应用粘贴的布局'; }, 1500);
                } catch (e) {
                    alert('导入失败: ' + e.message);
                }
            });
        }

        // — 重置 —
        const btnReset = $('#btnResetLayout');
        if (btnReset) {
            btnReset.addEventListener('click', function() {
                if (confirm('恢复默认布局？')) {
                    reset();
                    refreshUI();
                }
            });
        }

        // — 自定义 CSS 折叠 —
        const collapseToggle = $('#collapseCssToggle');
        const collapseBody = $('#collapseCssBody');
        if (collapseToggle && collapseBody) {
            collapseToggle.addEventListener('click', function() {
                const isOpen = collapseBody.classList.toggle('open');
                collapseToggle.classList.toggle('open', isOpen);
            });
        }

        // 初始刷新
        refreshUI();
    }

    // ======================== 初始化 ========================
    function init() {
        const loaded = load();
        if (!loaded) {
            applyLayout(DEFAULT_CONFIG);
        }
    }

    function deepMerge(base, override) {
        const result = JSON.parse(JSON.stringify(base));
        if (!override) return result;
        for (const key of Object.keys(override)) {
            if (override[key] && typeof override[key] === 'object' && !Array.isArray(override[key])) {
                result[key] = deepMerge(result[key] || {}, override[key]);
            } else if (override[key] !== undefined) {
                result[key] = override[key];
            }
        }
        return result;
    }

    function escAttr(str) {
        if (!str) return '';
        return String(str).replace(/&/g,'&amp;').replace(/"/g,'&quot;').replace(/</g,'&lt;').replace(/>/g,'&gt;');
    }

    // ======================== 公开 API ========================
    return {
        init,
        bindUI,
        applyLayout,
        exportLayout,
        importLayout,
        reset,
        getCurrent,
        getMode,
        save,
        NAV_LABELS,
        DEFAULT: DEFAULT_CONFIG
    };
})();
