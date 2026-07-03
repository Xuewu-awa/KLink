// ============================================================
// KLink Frontend App — UI 逻辑 & 桥接层
// ============================================================

(function() {
    'use strict';

    const $ = (sel) => document.querySelector(sel);
    const $$ = (sel) => document.querySelectorAll(sel);

    // ==================== 状态 ====================
    let serverRunning = false;
    let currentMode = 'local';
    let scanning = false;
    let statusTimer = null;
    let foundRooms = {};

    // ==================== 初始化 ====================
    var _readyCalled = false;
    window.onKLinkReady = function() {
        if (_readyCalled) return;
        _readyCalled = true;

        // 诊断：检测模块是否加载成功
        var missing = [];
        if (typeof ThemeEngine === 'undefined') missing.push('主题');
        if (typeof LayoutEngine === 'undefined') missing.push('布局');
        if (missing.length && window.KLink && window.KLink.showToast) {
            window.KLink.showToast('模块加载失败: ' + missing.join(', '));
        }

        try { if (typeof LayoutEngine !== 'undefined') LayoutEngine.init(); } catch(e) {}
        try { if (typeof ThemeEngine !== 'undefined') ThemeEngine.init(); } catch(e) {}
        try { loadVersion(); } catch(e) {}
        try { bindEvents(); } catch(e) {}
    };

    // 兜底：如果 Java 端 2 秒内没调 onKLinkReady，JS 自己初始化
    setTimeout(function() {
        if (!_readyCalled) window.onKLinkReady();
    }, 2000);

    // 从 Java 恢复已保存的设置
    window.onSettingsRestored = function(settings) {
        if (settings.savedMode) {
            currentMode = settings.savedMode;
            selectMode(currentMode, false);
        }
        if (settings.roomName) $('#inputRoomName').value = settings.roomName;
        if (settings.hostName) $('#inputHostName').value = settings.hostName;
        if (settings.remoteAddress) $('#inputRemoteAddr').value = settings.remoteAddress;
        if (settings.remotePort) $('#inputRemotePort').value = settings.remotePort;
    };

    function loadVersion() {
        try {
            const ver = window.KLink.getAppVersion();
            $('#versionText').textContent = 'v' + ver;
            $('#versionInfo').textContent = '版本: ' + ver;
        } catch(e) {}
    }

    // ==================== 事件绑定 ====================
    // ==================== 专注模式 ====================
    let focusHintTimer = null;   // 提示自动隐藏定时器
    let focusTapTimer = null;    // 双击检测定时器

    function enterFocusMode() {
        document.body.classList.add('immersive');
        // 把启动按钮搬到 body 下，避免被 sidebar 的 visibility 困住
        var btn = $('#btnLaunchGame');
        if (btn && btn.parentElement) {
            btn._klinkParent = btn.parentElement;
            btn._klinkNext = btn.nextSibling;
            document.body.appendChild(btn);
        }
        var hint = $('#focusHint');
        if (hint) {
            hint.classList.add('show');
            clearTimeout(focusHintTimer);
            focusHintTimer = setTimeout(function() {
                hint.classList.remove('show');
                focusHintTimer = null;
            }, 3000);
        }
    }

    function exitFocusMode() {
        document.body.classList.remove('immersive');
        // 把按钮搬回原位
        var btn = $('#btnLaunchGame');
        if (btn && btn._klinkParent) {
            if (btn._klinkNext) {
                btn._klinkParent.insertBefore(btn, btn._klinkNext);
            } else {
                btn._klinkParent.appendChild(btn);
            }
            btn._klinkParent = null;
            btn._klinkNext = null;
        }
        var hint = $('#focusHint');
        if (hint) hint.classList.remove('show');
        clearTimeout(focusTapTimer);
        clearTimeout(focusHintTimer);
        focusTapTimer = null;
        focusHintTimer = null;
    }

    function toggleFocusMode() {
        if (document.body.classList.contains('immersive')) {
            exitFocusMode();
        } else {
            enterFocusMode();
        }
    }

    function bindEvents() {
        $$('.nav-item').forEach(item => {
            item.addEventListener('click', () => switchTab(item.dataset.tab));
        });

        $$('.btn-mode').forEach(btn => {
            btn.addEventListener('click', () => selectMode(btn.dataset.mode, true));
        });

        $('#btnStartServer').addEventListener('click', startServer);
        $('#btnStopServer').addEventListener('click', stopServer);
        $('#btnScanRooms').addEventListener('click', scanRooms);
        $('#btnStopScan').addEventListener('click', stopScan);
        $('#btnScanMods').addEventListener('click', scanMods);
        $('#btnInstallMod').addEventListener('click', installMod);
        $('#btnLaunchGame').addEventListener('click', () => window.KLink.launchGame());

        // 专注模式（设置面板按钮 → 进入；右下 ✕ 按钮 → 退出）
        var enterBtn = $('#btnEnterFocus');
        if (enterBtn) enterBtn.addEventListener('click', enterFocusMode);

        var focusBtn = $('#btnFocusToggle');
        if (focusBtn) focusBtn.addEventListener('click', exitFocusMode);

        // 双击退出专注模式
        document.body.addEventListener('click', function(e) {
            if (!document.body.classList.contains('immersive')) return;
            if (e.target.closest('#btnLaunchGame')) return;
            if (e.target.closest('#btnFocusToggle')) return;

            if (focusTapTimer) {
                clearTimeout(focusTapTimer);
                focusTapTimer = null;
                exitFocusMode();
            } else {
                focusTapTimer = setTimeout(function() {
                    focusTapTimer = null;
                }, 350);
            }
        });
    }

    // ==================== 导航 ====================
    function switchTab(tabId) {
        $$('.nav-item').forEach(i => i.classList.remove('active'));
        document.querySelector(`[data-tab="${tabId}"]`).classList.add('active');
        $$('.tab-panel').forEach(p => p.classList.remove('active'));
        $(`#panel-${tabId}`).classList.add('active');

        // 自动加载对应数据
        if (tabId === 'mods') scanMods();
        if (tabId === 'server') refreshStatus();
        if (tabId === 'theme') {
            if (typeof ThemeEngine !== 'undefined' && ThemeEngine.bindUI) {
                ThemeEngine.bindUI();
            } else if (window.KLink && window.KLink.showToast) {
                window.KLink.showToast('主题模块加载失败');
            }
            if (typeof LayoutEngine !== 'undefined' && LayoutEngine.bindUI) {
                LayoutEngine.bindUI();
            }
        }
    }

    // ==================== 模式切换 ====================
    function selectMode(mode, fromClick) {
        currentMode = mode;
        $$('.btn-mode').forEach(b => b.classList.remove('active'));
        document.querySelector(`[data-mode="${mode}"]`).classList.add('active');

        const isRemote = (mode === 'remote');
        $('#cardRemoteConfig').style.display = isRemote ? 'block' : 'none';
        $('#cardRoomConfig').style.display = isRemote ? 'none' : 'block';

        // — 按钮文本：远程→连接，本地/局域网→启动 —
        $('#btnStartServer').textContent = isRemote ? '连接服务器' : '启动服务器';

        // — Hero 元数据 —
        const heroMode = $('#heroMode');
        if (heroMode) heroMode.textContent = modeLabel(mode);

        // — 未运行时更新 Hero 副标题 —
        if (!serverRunning) {
            $('#heroSubtitle').textContent = isRemote ? '输入地址以连接' : '选择模式以开始';
        }

        if (fromClick && serverRunning) {
            log('模式已更改，请重启服务器生效', '');
        }
    }

    // ==================== 服务器控制 ====================
    function startServer() {
        const roomName = $('#inputRoomName').value || 'KARDS Room';
        const hostName = $('#inputHostName').value || 'Host';
        let config = { roomName, hostName };

        if (currentMode === 'remote') {
            config.remoteAddress = $('#inputRemoteAddr').value.trim();
            config.remotePort = parseInt($('#inputRemotePort').value) || 5231;
            if (!config.remoteAddress) {
                log('请输入远程服务器地址', 'error');
                return;
            }
        }

        log(`正在启动服务器 (${modeLabel(currentMode)})...`, '');
        window.KLink.startServer(currentMode, JSON.stringify(config));
    }

    function stopServer() {
        window.KLink.stopServer();
        if (statusTimer) clearInterval(statusTimer);
        statusTimer = null;
    }

    function setServerUI(running) {
        serverRunning = running;
        $('#btnStartServer').disabled = running;
        $('#btnStopServer').disabled = !running;
        $('#btnLaunchGame').disabled = !running;

        // — 侧边栏状态 —
        const dot = $('#statusIndicator').querySelector('.status-dot');
        const text = $('#statusIndicator').querySelector('.status-text');

        // — Hero 状态 —
        const heroInd = $('#heroIndicator');
        const heroTitle = $('#heroTitle');
        const heroSub = $('#heroSubtitle');

        const isRemote = (currentMode === 'remote');

        if (running) {
            dot.className = 'status-dot online';
            text.textContent = '运行中';
            heroInd.className = 'status-hero-indicator online';
            heroTitle.textContent = isRemote ? '已连接至远程服务器' : '服务器运行中';
            heroSub.textContent = (isRemote ? '远程转发' : modeLabel(currentMode)).toUpperCase() + ' · 端口 5231';
            if (!statusTimer) {
                statusTimer = setInterval(refreshStatus, 3000);
            }
        } else {
            dot.className = 'status-dot offline';
            text.textContent = '未连接';
            heroInd.className = 'status-hero-indicator offline';
            heroTitle.textContent = isRemote ? '未连接' : '服务器未启动';
            heroSub.textContent = isRemote ? '输入地址以连接' : '选择模式以开始';
            if (statusTimer) {
                clearInterval(statusTimer);
                statusTimer = null;
            }
        }
    }

    function refreshStatus() {
        try {
            const status = JSON.parse(window.KLink.pollStatus());
            if (status.running) setServerUI(true);
            else if (serverRunning) setServerUI(false);
        } catch(e) {}
    }

    // ==================== 房间发现 ====================
    function scanRooms() {
        if (scanning) return;
        scanning = true;
        foundRooms = {};
        $('#btnScanRooms').disabled = true;
        $('#btnStopScan').disabled = false;
        $('#roomList').innerHTML = '<div class="empty-hint">正在扫描...</div>';
        window.KLink.startScanRooms();
        log('开始扫描局域网房间...', '');

        // 10 秒后自动停止
        setTimeout(() => {
            if (scanning) stopScan();
        }, 10000);
    }

    function stopScan() {
        scanning = false;
        $('#btnScanRooms').disabled = false;
        $('#btnStopScan').disabled = true;
        window.KLink.stopScanRooms();
        log(`扫描结束，发现 ${Object.keys(foundRooms).length} 个房间`, '');
    }

    window.onRoomFound = function(info) {
        const key = info.address + ':' + (info.httpPort || 5231);
        if (foundRooms[key]) return; // 去重
        foundRooms[key] = info;

        const container = $('#roomList');
        if (container.querySelector('.empty-hint')) {
            container.innerHTML = '';
        }

        const room = document.createElement('div');
        room.className = 'room-item';
        room.id = 'room-' + key.replace(/[.:]/g, '_');
        room.innerHTML = `
            <div>
                <div class="room-name">${esc(info.roomName || 'KARDS Room')}</div>
                <div class="room-info">${esc(info.hostName || 'Host')} — ${esc(info.address)}:${info.httpPort || 5231}</div>
            </div>
            <div class="room-actions">
                <span class="room-players">${info.players || 0} 人在线</span>
                <button class="btn btn-accent btn-sm" data-join="${esc(info.address)}" data-port="${info.httpPort || 5231}">加入</button>
            </div>
        `;
        container.appendChild(room);

        // 绑定加入按钮
        room.querySelector('.btn-accent').addEventListener('click', function() {
            window.KLink.connectToRoom(this.dataset.join, parseInt(this.dataset.port));
            switchTab('server');
        });
    };

    // ==================== 模组管理 ====================
    function scanMods() {
        try {
            const modsJson = window.KLink.getModList();
            const mods = JSON.parse(modsJson);
            renderModList(mods);
            log(`扫描完成: ${mods.length} 个模组`, 'success');
        } catch(e) {
            log('扫描模组失败', 'error');
        }
    }

    function installMod() {
        // Android 文件选择由 Java 端发起
        log('请通过文件管理器选择 PAK 文件...', '');
        window.KLink.installMod();
    }

    function renderModList(mods) {
        const container = $('#modList');
        if (!mods || mods.length === 0) {
            container.innerHTML = '<div class="empty-hint">暂无模组，点击扫描或安装</div>';
            return;
        }
        container.innerHTML = mods.map(m => `
            <div class="mod-item">
                <div class="mod-info">
                    <div class="room-name">${esc(m.name || 'Unknown')}</div>
                    <div class="room-info">${esc(m.size || '?')} — 优先级: ${m.priority || 0}</div>
                </div>
                <div class="mod-actions">
                    <button class="btn btn-sm ${m.enabled ? 'btn-danger' : 'btn-accent'}"
                            data-toggle="${esc(m.id)}" data-enable="${!m.enabled}">
                        ${m.enabled ? '禁用' : '启用'}
                    </button>
                    <button class="btn btn-danger btn-sm" data-delete="${esc(m.id)}">卸载</button>
                </div>
            </div>
        `).join('');

        // 绑定事件
        container.querySelectorAll('[data-toggle]').forEach(btn => {
            btn.addEventListener('click', function() {
                window.KLink.toggleMod(this.dataset.toggle, this.dataset.enable === 'true');
                scanMods();
            });
        });
        container.querySelectorAll('[data-delete]').forEach(btn => {
            btn.addEventListener('click', function() {
                window.KLink.uninstallMod(this.dataset.delete);
                scanMods();
            });
        });
    }

    // ==================== 服务器事件回调 ====================
    window.onServerEvent = function(event) {
        const type = event.type || 'info';
        const msg = event.message || '';

        if (type === 'error') {
            log(msg, 'error');
            setServerUI(false);
        } else if (type === 'warn') {
            log(msg, 'warn');
        } else if (type === 'status') {
            if (msg.includes('已启动')) setServerUI(true);
            if (msg.includes('已停止')) setServerUI(false);
            log(msg, 'success');
        } else {
            // 有可能是完整的状态 JSON
            if (event.running !== undefined) {
                setServerUI(event.running);
            }
            log(msg || JSON.stringify(event), '');
        }
    };

    // ==================== 控制台 ====================
    function log(message, cls) {
        const consoleEl = $('#console');
        const line = document.createElement('div');
        line.className = 'console-line' + (cls ? ' ' + cls : '');
        const time = new Date().toLocaleTimeString();
        line.textContent = `[${time}] ${message}`;
        consoleEl.appendChild(line);
        consoleEl.scrollTop = consoleEl.scrollHeight;
        while (consoleEl.children.length > 200) {
            consoleEl.removeChild(consoleEl.firstChild);
        }
    }

    // ==================== 工具 ====================
    function esc(str) {
        if (!str) return '';
        return String(str).replace(/&/g, '&amp;').replace(/</g, '&lt;')
            .replace(/>/g, '&gt;').replace(/"/g, '&quot;').replace(/'/g, '&#39;');
    }

    function modeLabel(mode) {
        return { local: '本地', lan: '局域网', remote: '远程转发' }[mode] || mode;
    }

})();
