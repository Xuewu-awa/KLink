// ============================================================
// KLink Theme Engine — 主题自定义系统
// 所有颜色/背景/排版/噪点均可通过 CSS 变量实时切换
// 支持预设 / 导出 / 导入 / localStorage 持久化
// ============================================================

const ThemeEngine = (function() {
    'use strict';

    // ======================== 默认主题 ========================
    const DEFAULT_THEME = {
        name: 'Deep Dark',
        version: 1,
        colors: {
            bgDeep:        '#050508',
            bgBase:        '#0a0a0f',
            bgSurface:     '#0e0e14',
            bgElevated:    '#14141c',
            bgOverlay:     '#1a1a24',
            textPrimary:   '#ececf0',
            textSecondary: '#a0a0b0',
            textTertiary:  '#606070',
            textDisabled:  '#40404c',
            accent:        '#9ed9cc',
            accentStrong:  '#b8f0e4',
            accentMuted:   '#6a9e92',
            amber:         '#e8c57a',
            danger:        '#e06c60',
            success:       '#7ec99c'
        },
        background: {
            type: 'solid',       // 'solid' | 'gradient' | 'image' | 'video'
            value: '#050508',
            gradient: 'linear-gradient(160deg, #050508 0%, #0a0a10 50%, #06060c 100%)',
            imageUrl: '',
            imageOpacity: 0.12,
            imageBlur: '0px',
            blend: 'normal',      // 'opaque' | 'normal' | 'frosted'
            videoUrl: ''
        },
        spacing: 'comfortable',  // 'compact' | 'comfortable' | 'spacious'
        radius: 'rounded',       // 'sharp' | 'rounded' | 'pill'
        noise: true,
        noiseOpacity: 0.025
    };

    // ======================== 内置预设 ========================
    const PRESETS = {
        'deep-dark': {
            name: 'Deep Dark',
            colors: {
                bgDeep: '#050508', bgBase: '#0a0a0f', bgSurface: '#0e0e14',
                bgElevated: '#14141c', bgOverlay: '#1a1a24',
                textPrimary: '#ececf0', textSecondary: '#a0a0b0',
                textTertiary: '#606070', textDisabled: '#40404c',
                accent: '#9ed9cc', accentStrong: '#b8f0e4', accentMuted: '#6a9e92',
                amber: '#e8c57a', danger: '#e06c60', success: '#7ec99c'
            },
            background: { type: 'solid', value: '#050508', gradient: 'linear-gradient(160deg, #050508 0%, #0a0a10 50%, #06060c 100%)', imageUrl: '', imageOpacity: 0.12, imageBlur: '0px', blend: 'normal' },
            spacing: 'comfortable', radius: 'rounded', noise: true, noiseOpacity: 0.025
        },
        'moon-light': {
            name: 'Moon Light',
            colors: {
                bgDeep: '#e8e4dc', bgBase: '#edeae3', bgSurface: '#f2f0ea',
                bgElevated: '#ece9e2', bgOverlay: '#e5e1d8',
                textPrimary: '#2a2822', textSecondary: '#5e5b52',
                textTertiary: '#8c887c', textDisabled: '#bab6aa',
                accent: '#5c8a78', accentStrong: '#3d6b58', accentMuted: '#8ab8a4',
                amber: '#b88430', danger: '#c05040', success: '#4a8e60'
            },
            background: { type: 'solid', value: '#e8e4dc', gradient: 'linear-gradient(160deg, #e8e4dc 0%, #f2f0ea 50%, #e5e1d8 100%)', imageUrl: '', imageOpacity: 0.04, imageBlur: '0px', blend: 'normal' },
            spacing: 'comfortable', radius: 'rounded', noise: false, noiseOpacity: 0
        },
        'amber-gold': {
            name: 'Amber Gold',
            colors: {
                bgDeep: '#12100a', bgBase: '#18150e', bgSurface: '#1e1a12',
                bgElevated: '#262118', bgOverlay: '#2e281e',
                textPrimary: '#efe8d8', textSecondary: '#b8a888',
                textTertiary: '#786848', textDisabled: '#504838',
                accent: '#d4a848', accentStrong: '#e8c060', accentMuted: '#a07828',
                amber: '#e8c878', danger: '#d46848', success: '#88c878'
            },
            background: { type: 'gradient', value: '#12100a', gradient: 'linear-gradient(160deg, #12100a 0%, #1c1810 50%, #100e08 100%)', imageUrl: '', imageOpacity: 0.08, imageBlur: '0px', blend: 'normal' },
            spacing: 'comfortable', radius: 'rounded', noise: true, noiseOpacity: 0.02
        },
        'sunset': {
            name: 'Sunset',
            colors: {
                bgDeep: '#0f0a0c', bgBase: '#150e10', bgSurface: '#1c1216',
                bgElevated: '#24181c', bgOverlay: '#2c1e22',
                textPrimary: '#f0e8e4', textSecondary: '#c0a898',
                textTertiary: '#807060', textDisabled: '#504840',
                accent: '#e8946c', accentStrong: '#f0ac88', accentMuted: '#c07050',
                amber: '#e8c87c', danger: '#e06858', success: '#8cc87c'
            },
            background: { type: 'gradient', value: '#0f0a0c', gradient: 'linear-gradient(160deg, #0f0a0c 0%, #1a1014 50%, #120a0e 100%)', imageUrl: '', imageOpacity: 0.1, imageBlur: '0px', blend: 'normal' },
            spacing: 'comfortable', radius: 'rounded', noise: true, noiseOpacity: 0.02
        },
        'ocean': {
            name: 'Ocean',
            colors: {
                bgDeep: '#060a12', bgBase: '#0a0f1a', bgSurface: '#0e1422',
                bgElevated: '#141c2c', bgOverlay: '#1a2436',
                textPrimary: '#e8eef4', textSecondary: '#98b0c8',
                textTertiary: '#587088', textDisabled: '#385068',
                accent: '#6cace8', accentStrong: '#88c4f0', accentMuted: '#4e88c0',
                amber: '#d4b858', danger: '#d46868', success: '#6cc8a0'
            },
            background: { type: 'gradient', value: '#060a12', gradient: 'linear-gradient(160deg, #060a12 0%, #0c1424 50%, #060c18 100%)', imageUrl: '', imageOpacity: 0.1, imageBlur: '0px', blend: 'normal' },
            spacing: 'comfortable', radius: 'rounded', noise: true, noiseOpacity: 0.02
        }
    };

    // ======================== 内部状态 ========================
    let _current = null;

    // ======================== 工具函数 ========================
    function hexToRgb(hex) {
        const m = /^#?([a-f\d]{2})([a-f\d]{2})([a-f\d]{2})$/i.exec(hex);
        return m ? `${parseInt(m[1],16)}, ${parseInt(m[2],16)}, ${parseInt(m[3],16)}` : '255,255,255';
    }

    function alphaColor(hex, alpha) {
        return `rgba(${hexToRgb(hex)}, ${alpha})`;
    }

    // ======================== 应用主题 ========================
    function applyTheme(config) {
        _current = deepMerge(DEFAULT_THEME, config);

        const root = document.documentElement;
        const c = _current.colors;
        const bg = _current.background;

        // — 颜色变量 —
        root.style.setProperty('--bg-deep',        c.bgDeep);
        root.style.setProperty('--bg-base',        c.bgBase);
        root.style.setProperty('--bg-surface',     c.bgSurface);
        root.style.setProperty('--bg-elevated',    c.bgElevated);
        root.style.setProperty('--bg-overlay',     c.bgOverlay);
        root.style.setProperty('--text-primary',   c.textPrimary);
        root.style.setProperty('--text-secondary', c.textSecondary);
        root.style.setProperty('--text-tertiary',  c.textTertiary);
        root.style.setProperty('--text-disabled',  c.textDisabled);
        root.style.setProperty('--accent',         c.accent);
        root.style.setProperty('--accent-strong',  c.accentStrong);
        root.style.setProperty('--accent-muted',   c.accentMuted);
        root.style.setProperty('--accent-surface', alphaColor(c.accent, 0.08));
        root.style.setProperty('--accent-border',  alphaColor(c.accent, 0.18));
        root.style.setProperty('--accent-glow',    `0 0 16px ${alphaColor(c.accent, 0.12)}`);
        root.style.setProperty('--amber',          c.amber);
        root.style.setProperty('--amber-surface',  alphaColor(c.amber, 0.08));
        root.style.setProperty('--amber-border',   alphaColor(c.amber, 0.2));
        root.style.setProperty('--danger',         c.danger);
        root.style.setProperty('--danger-surface', alphaColor(c.danger, 0.1));
        root.style.setProperty('--danger-border',  alphaColor(c.danger, 0.25));
        root.style.setProperty('--success',        c.success);
        root.style.setProperty('--success-surface',alphaColor(c.success, 0.1));

        // hairline / rule 根据背景亮度自适应
        const bgRgb = hexToRgb(c.bgDeep);
        const alphaLow  = isLight(c.bgDeep) ? '0.08' : '0.05';
        const alphaHigh = isLight(c.bgDeep) ? '0.12' : '0.07';
        root.style.setProperty('--hairline', `rgba(${bgRgb}, ${alphaLow})`);
        root.style.setProperty('--rule',     `rgba(${bgRgb}, ${alphaHigh})`);

        // — 背景 —
        let bodyBg = bg.value;
        if (bg.type === 'gradient') {
            bodyBg = bg.gradient;
        } else if (bg.type === 'image' && bg.imageUrl) {
            bodyBg = bg.value; // fallback solid behind image
        }
        root.style.setProperty('--body-bg', bodyBg);
        document.body.style.background = bodyBg;

        // 背景图片
        const existingOverlay = document.getElementById('theme-bg-overlay');
        if (bg.type === 'image' && bg.imageUrl) {
            if (!existingOverlay) {
                const overlay = document.createElement('div');
                overlay.id = 'theme-bg-overlay';
                overlay.style.cssText = `
                    position: fixed; inset: 0; z-index: 0; pointer-events: none;
                    background-size: cover; background-position: center;
                    background-repeat: no-repeat;
                `;
                document.body.prepend(overlay);
            }
            const overlay = document.getElementById('theme-bg-overlay');
            overlay.style.backgroundImage = `url(${bg.imageUrl})`;

            const blend = bg.blend || 'normal';
            if (blend === 'opaque') {
                overlay.style.opacity = 1;
                overlay.style.filter = 'none';
                overlay.style.backdropFilter = 'none';
            } else if (blend === 'frosted') {
                overlay.style.opacity = 0.55;
                overlay.style.filter = 'blur(12px) saturate(1.2)';
                overlay.style.backdropFilter = 'blur(12px) saturate(1.2)';
            } else {
                overlay.style.opacity = bg.imageOpacity;
                overlay.style.filter = `blur(${bg.imageBlur || '0px'})`;
                overlay.style.backdropFilter = 'none';
            }
        }
        // 背景视频
        const existingVideo = document.getElementById('theme-bg-video');
        if (bg.type === 'video' && bg.videoUrl) {
            if (!existingVideo) {
                const video = document.createElement('video');
                video.id = 'theme-bg-video';
                video.autoplay = true;
                video.loop = true;
                video.muted = true;
                video.playsInline = true;
                video.style.cssText = `
                    position: fixed; inset: 0; z-index: 0; pointer-events: none;
                    object-fit: cover; width: 100%; height: 100%;
                `;
                document.body.prepend(video);
            }
            const video = document.getElementById('theme-bg-video');
            video.src = bg.videoUrl;
            video.style.opacity = bg.imageOpacity || 0.5;
            video.play().catch(function(){});
        } else if (existingVideo) {
            existingVideo.pause();
            existingVideo.remove();
        }

        // 清理图片 overlay（视频和图片互斥）
        if (bg.type === 'video' && existingOverlay) {
            existingOverlay.remove();
        }

        // — 间距级别 —
        const spacingMap = {
            compact:     { xs:'2px', sm:'4px', md:'10px', lg:'16px', xl:'22px', xl2:'32px' },
            comfortable: { xs:'4px', sm:'8px', md:'16px', lg:'24px', xl:'32px', xl2:'48px' },
            spacious:    { xs:'6px', sm:'12px',md:'22px', lg:'32px', xl:'44px', xl2:'64px' }
        };
        const sp = spacingMap[_current.spacing] || spacingMap.comfortable;
        root.style.setProperty('--space-xs',  sp.xs);
        root.style.setProperty('--space-sm',  sp.sm);
        root.style.setProperty('--space-md',  sp.md);
        root.style.setProperty('--space-lg',  sp.lg);
        root.style.setProperty('--space-xl',  sp.xl);
        root.style.setProperty('--space-2xl', sp.xl2);

        // — 圆角级别 —
        const radiusMap = {
            sharp:   { sm:'2px', md:'4px', lg:'6px' },
            rounded: { sm:'4px', md:'6px', lg:'10px' },
            pill:    { sm:'8px', md:'14px',lg:'20px' }
        };
        const rd = radiusMap[_current.radius] || radiusMap.rounded;
        root.style.setProperty('--radius-sm', rd.sm);
        root.style.setProperty('--radius-md', rd.md);
        root.style.setProperty('--radius-lg', rd.lg);

        // — 噪点 —
        const noiseEl = document.getElementById('theme-noise');
        if (_current.noise) {
            if (!noiseEl) {
                const el = document.createElement('div');
                el.id = 'theme-noise';
                el.style.cssText = `
                    position: fixed; inset: 0; pointer-events: none; z-index: 9998;
                    background-image: url("data:image/svg+xml,%3Csvg viewBox='0 0 256 256' xmlns='http://www.w3.org/2000/svg'%3E%3Cfilter id='n'%3E%3CfeTurbulence type='fractalNoise' baseFrequency='0.85' numOctaves='4' stitchTiles='stitch'/%3E%3C/filter%3E%3Crect width='100%25' height='100%25' filter='url(%23n)'/%3E%3C/svg%3E");
                `;
                document.body.appendChild(el);
            }
            const el = document.getElementById('theme-noise');
            el.style.opacity = _current.noiseOpacity;
        } else if (noiseEl) {
            noiseEl.remove();
        }

        // 同时移除 CSS 里的 body::after 噪点，避免双层
        // (通过 class 控制)
        document.body.classList.toggle('noise-native', false);

        return _current;
    }

    // ======================== 辅助 ========================
    function isLight(hex) {
        const m = /^#?([a-f\d]{2})([a-f\d]{2})([a-f\d]{2})$/i.exec(hex);
        if (!m) return false;
        // 相对亮度 (sRGB)
        const r = parseInt(m[1], 16) / 255;
        const g = parseInt(m[2], 16) / 255;
        const b = parseInt(m[3], 16) / 255;
        const lum = 0.2126 * r + 0.7152 * g + 0.0722 * b;
        return lum > 0.5;
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

    // ======================== 导出 ========================
    function exportTheme() {
        const theme = getCurrent();
        const json = JSON.stringify(theme, null, 2);
        // 优先走 Java 桥（Android 系统文件选择器）
        if (window.KLink && window.KLink.exportTheme) {
            window.KLink.exportTheme(json);
        } else {
            // fallback: 浏览器 Blob 下载
            const blob = new Blob([json], { type: 'application/json' });
            const url = URL.createObjectURL(blob);
            const a = document.createElement('a');
            a.href = url;
            a.download = `klink-theme-${sanitizeFilename(theme.name || 'custom')}.json`;
            document.body.appendChild(a);
            a.click();
            document.body.removeChild(a);
            URL.revokeObjectURL(url);
        }
        return json;
    }

    function shareTheme() {
        const theme = getCurrent();
        const json = JSON.stringify(theme, null, 2);
        if (window.KLink && window.KLink.shareTheme) {
            window.KLink.shareTheme(json);
        } else if (navigator.share) {
            navigator.share({ title: 'KLink Theme', text: json }).catch(function(){});
        } else {
            // fallback: 复制到剪贴板
            if (navigator.clipboard) {
                navigator.clipboard.writeText(json).then(function() {
                    alert('主题 JSON 已复制到剪贴板');
                }).catch(function() {
                    alert(json);
                });
            } else {
                alert(json);
            }
        }
        return json;
    }

    function sanitizeFilename(name) {
        return name.replace(/[^a-zA-Z0-9_\-\u4e00-\u9fff]/g, '_').substring(0, 40) || 'theme';
    }

    // ======================== 导入 ========================
    function importTheme(jsonStr) {
        let config;
        try {
            config = JSON.parse(jsonStr);
        } catch (e) {
            throw new Error('JSON 解析失败: ' + e.message);
        }
        if (!config.colors && !config.background && !config.spacing) {
            throw new Error('无效的主题文件：缺少 colors / background / spacing 字段');
        }
        applyTheme(config);
        save();
        return config;
    }

    function importFromFile(file) {
        return new Promise((resolve, reject) => {
            const reader = new FileReader();
            reader.onload = function() {
                try {
                    const config = importTheme(reader.result);
                    resolve(config);
                } catch (e) {
                    reject(e);
                }
            };
            reader.onerror = function() {
                reject(new Error('文件读取失败'));
            };
            reader.readAsText(file);
        });
    }

    /**
     * 通过 Java 桥打开系统文件选择器导入主题。
     * 结果通过 window.onThemeFileLoaded(json) 回调返回。
     */
    function pickThemeFileViaBridge() {
        if (window.KLink && window.KLink.pickThemeFile) {
            window.KLink.pickThemeFile();
            return true;
        }
        return false;
    }

    /**
     * Java 桥回调 — 用户选好主题文件后，Java 读取内容并调用此函数。
     */
    window.onThemeFileLoaded = function(jsonStr) {
        try {
            importTheme(jsonStr);
            // 通过暴露的刷新钩子更新 UI
            if (window.__themeRefreshUI) window.__themeRefreshUI();
            if (window.KLink && window.KLink.showToast) {
                window.KLink.showToast('主题导入成功');
            }
        } catch (e) {
            if (window.KLink && window.KLink.showToast) {
                window.KLink.showToast('导入失败: ' + e.message);
            }
        }
    };

    // ======================== 预设 ========================
    function applyPreset(presetKey) {
        const preset = PRESETS[presetKey];
        if (!preset) throw new Error('未知预设: ' + presetKey);
        applyTheme(preset);
        save();
        return preset;
    }

    function getPresets() {
        return Object.keys(PRESETS).map(k => ({ key: k, name: PRESETS[k].name }));
    }

    // ======================== 持久化 ========================
    const STORAGE_KEY = 'klink_theme_v2';

    function save() {
        try {
            localStorage.setItem(STORAGE_KEY, JSON.stringify(_current));
        } catch (e) {
            // localStorage 满或不可用，静默失败
        }
    }

    function load() {
        try {
            const raw = localStorage.getItem(STORAGE_KEY);
            if (raw) {
                const saved = JSON.parse(raw);
                if (saved && saved.colors) {
                    applyTheme(saved);
                    return true;
                }
            }
        } catch (e) { /* ignore */ }
        return false;
    }

    function reset() {
        localStorage.removeItem(STORAGE_KEY);
        applyTheme(DEFAULT_THEME);
    }

    // ======================== 查询 ========================
    function getCurrent() {
        return JSON.parse(JSON.stringify(_current || DEFAULT_THEME));
    }

    function getColor(key) {
        return (_current && _current.colors && _current.colors[key]) || DEFAULT_THEME.colors[key] || '#000';
    }

    // ======================== 初始化 ========================
    function init() {
        // 先禁用 CSS 原生噪点 (body::after)，改为 JS 控制的噪点层
        document.body.classList.add('noise-native');
        const style = document.createElement('style');
        style.id = 'theme-noise-native-disable';
        style.textContent = '.noise-native::after { display: none !important; }';
        document.head.appendChild(style);

        // 尝试从 localStorage 恢复
        const loaded = load();
        if (!loaded) {
            applyTheme(DEFAULT_THEME);
        }
    }

    // ======================== UI 绑定 ========================
    function bindUI() {
        const $ = (sel) => document.querySelector(sel);
        const $$ = (sel) => document.querySelectorAll(sel);

        // — 辅助：从当前主题刷新所有 UI 控件 —
        function refreshUI() {
            const theme = getCurrent();

            // 预设选中态
            $$('.preset-card').forEach(card => {
                card.classList.toggle('active', card.dataset.preset === _current.name);
            });

            // 颜色选择器
            $$('.color-input-wrap input[type="color"]').forEach(input => {
                const key = input.dataset.colorKey;
                if (key && theme.colors[key]) {
                    input.value = theme.colors[key];
                    const hexEl = input.parentElement.parentElement.querySelector('.color-hex');
                    if (hexEl) hexEl.textContent = theme.colors[key];
                }
            });

            // 背景类型
            const bgType = theme.background.type || 'solid';
            $$('#bgTypeRow .bg-option').forEach(b => {
                b.classList.toggle('active', b.dataset.type === bgType);
            });
            $('#bgGradientRow').style.display = (bgType === 'gradient') ? 'block' : 'none';
            $('#bgImageRow').style.display = (bgType === 'image') ? 'block' : 'none';
            $('#bgVideoRow').style.display = (bgType === 'video') ? 'block' : 'none';
            if (bgType === 'video') {
                $('#inputBgVideoUrl').value = theme.background.videoUrl || '';
            }
            if (bgType === 'gradient') {
                $('#inputBgGradient').value = theme.background.gradient || '';
            }
            if (bgType === 'image') {
                $('#inputBgImageUrl').value = theme.background.imageUrl || '';

                // blend 模式
                const blend = theme.background.blend || 'normal';
                $$('#bgBlendRow .option-btn').forEach(b => {
                    b.classList.toggle('active', b.dataset.blend === blend);
                });
                const sliderRow = $('#bgOpacitySliderRow');
                if (sliderRow) sliderRow.style.display = (blend === 'normal') ? 'flex' : 'none';

                if (blend === 'opaque') {
                    $('#rangeBgOpacity').value = 100;
                    $('#valBgOpacity').textContent = '100%';
                } else if (blend === 'frosted') {
                    $('#rangeBgOpacity').value = 55;
                    $('#valBgOpacity').textContent = '55%';
                } else {
                    $('#rangeBgOpacity').value = Math.round((theme.background.imageOpacity || 0.12) * 100);
                    $('#valBgOpacity').textContent = Math.round((theme.background.imageOpacity || 0.12) * 100) + '%';
                }
            }
            updateBgPreview();

            // 间距
            $$('#spacingRow .option-btn').forEach(b => {
                b.classList.toggle('active', b.dataset.spacing === theme.spacing);
            });

            // 圆角
            $$('#radiusRow .option-btn').forEach(b => {
                b.classList.toggle('active', b.dataset.radius === theme.radius);
            });

            // 噪点
            $('#toggleNoise').checked = theme.noise !== false;
            $('#rangeNoiseOpacity').value = Math.round((theme.noiseOpacity || 0.025) * 1000);
            $('#valNoiseOpacity').textContent = (theme.noiseOpacity || 0.025).toFixed(3);
            $('#noiseOpacityRow').style.display = theme.noise !== false ? 'flex' : 'none';
        }

        function updateBgPreview() {
            const theme = getCurrent();
            const bg = theme.background;
            const preview = $('#bgPreview');
            const label = $('#bgPreviewLabel');
            if (!preview) return;

            if (bg.type === 'solid') {
                preview.style.backgroundImage = 'none';
                preview.style.backgroundColor = bg.value || theme.colors.bgDeep;
                label.textContent = '纯色';
            } else if (bg.type === 'gradient') {
                preview.style.backgroundColor = 'transparent';
                preview.style.backgroundImage = bg.gradient || '';
                label.textContent = '渐变';
            } else if (bg.type === 'image' && bg.imageUrl) {
                preview.style.backgroundColor = bg.value || theme.colors.bgDeep;
                preview.style.backgroundImage = `url(${bg.imageUrl})`;
                label.textContent = '图片';
            } else if (bg.type === 'video' && bg.videoUrl) {
                preview.style.backgroundImage = 'none';
                preview.style.backgroundColor = theme.colors.bgDeep;
                label.textContent = '视频';
            } else {
                preview.style.backgroundImage = 'none';
                preview.style.backgroundColor = theme.colors.bgDeep;
                label.textContent = '未设置';
            }
        }

        // — 防止重复绑定 —
        if (document.getElementById('theme-ui-bound')) return;
        const bound = document.createElement('meta');
        bound.id = 'theme-ui-bound';
        document.head.appendChild(bound);

        // — 渲染预设卡片 —
        const presetGrid = $('#presetGrid');
        if (presetGrid) {
            const presets = getPresets();
            presetGrid.innerHTML = presets.map(p => {
                const t = PRESETS[p.key];
                const bars = [
                    t.colors.bgDeep, t.colors.bgSurface, t.colors.bgElevated,
                    t.colors.accent, t.colors.amber
                ].map(c => `<span class="preset-swatch-bar" style="background:${c}"></span>`).join('');
                return `
                    <div class="preset-card" data-preset="${escAttr(t.name)}" data-key="${p.key}">
                        <div class="preset-swatch">${bars}</div>
                        <span class="preset-name">${escAttr(t.name)}</span>
                    </div>`;
            }).join('');

            presetGrid.querySelectorAll('.preset-card').forEach(card => {
                card.addEventListener('click', function() {
                    applyPreset(this.dataset.key);
                    refreshUI();
                });
            });
        }

        // — 渲染颜色选择器 —
        const colorDefs = [
            { group: 'colorGroupBg', keys: [
                { key:'bgDeep',     label:'最深' },
                { key:'bgBase',     label:'基础' },
                { key:'bgSurface',  label:'表面' },
                { key:'bgElevated', label:'抬升' },
                { key:'bgOverlay',  label:'叠加' }
            ]},
            { group: 'colorGroupText', keys: [
                { key:'textPrimary',   label:'主文字' },
                { key:'textSecondary', label:'次文字' },
                { key:'textTertiary',  label:'三级' },
                { key:'textDisabled',  label:'禁用' }
            ]},
            { group: 'colorGroupAccent', keys: [
                { key:'accent',       label:'主色' },
                { key:'accentStrong', label:'亮色' },
                { key:'accentMuted',  label:'暗色' },
                { key:'amber',        label:'琥珀' }
            ]},
            { group: 'colorGroupSemantic', keys: [
                { key:'danger',  label:'危险' },
                { key:'success', label:'成功' }
            ]}
        ];

        colorDefs.forEach(def => {
            const container = $('#' + def.group);
            if (!container) return;
            container.innerHTML = def.keys.map(k => `
                <div class="color-row">
                    <span class="color-label">${k.label}</span>
                    <div class="color-input-wrap" style="background:${getColor(k.key)}">
                        <input type="color" data-color-key="${k.key}" value="${getColor(k.key)}">
                    </div>
                    <span class="color-hex">${getColor(k.key)}</span>
                </div>
            `).join('');

            container.querySelectorAll('input[type="color"]').forEach(input => {
                input.addEventListener('input', function() {
                    const key = this.dataset.colorKey;
                    const hex = this.value;

                    // 更新圆形色块背景
                    this.parentElement.style.background = hex;

                    // 更新 hex 文字
                    const hexEl = this.parentElement.parentElement.querySelector('.color-hex');
                    if (hexEl) hexEl.textContent = hex;

                    // 实时应用
                    const current = getCurrent();
                    current.colors[key] = hex;
                    applyTheme(current);
                    save();
                    refreshUI(); // 刷新预设选中态等
                });
            });
        });

        // — 背景类型切换 —
        $$('#bgTypeRow .bg-option').forEach(btn => {
            btn.addEventListener('click', function() {
                const type = this.dataset.type;
                $$('#bgTypeRow .bg-option').forEach(b => b.classList.remove('active'));
                this.classList.add('active');

                $('#bgGradientRow').style.display = (type === 'gradient') ? 'block' : 'none';
                $('#bgImageRow').style.display = (type === 'image') ? 'block' : 'none';
                $('#bgVideoRow').style.display = (type === 'video') ? 'block' : 'none';

                const current = getCurrent();
                current.background.type = type;
                applyTheme(current);
                save();
                updateBgPreview();
            });
        });

        // — 渐变输入 —
        const inputBgGradient = $('#inputBgGradient');
        if (inputBgGradient) {
            inputBgGradient.addEventListener('input', function() {
                const current = getCurrent();
                current.background.gradient = this.value;
                applyTheme(current);
                save();
                updateBgPreview();
            });
        }

        // — 图片 URL 输入 —
        const inputBgImageUrl = $('#inputBgImageUrl');
        if (inputBgImageUrl) {
            inputBgImageUrl.addEventListener('input', function() {
                const current = getCurrent();
                current.background.imageUrl = this.value;
                applyTheme(current);
                save();
                updateBgPreview();
            });
        }

        // — 图片文件选择（优先 Java 桥，fallback 浏览器 file input） —
        $('#btnBgImageFile').addEventListener('click', function() {
            if (window.KLink && window.KLink.pickBgImageFile) {
                window.KLink.pickBgImageFile();
            } else {
                $('#inputBgImageFile').click();
            }
        });
        $('#inputBgImageFile').addEventListener('change', function() {
            const file = this.files[0];
            if (!file) return;
            const reader = new FileReader();
            reader.onload = function(e) {
                applyBgImageUrl(e.target.result);
            };
            reader.readAsDataURL(file);
        });

        function applyBgImageUrl(dataUri) {
            $('#inputBgImageUrl').value = dataUri;
            const current = getCurrent();
            current.background.imageUrl = dataUri;
            applyTheme(current);
            save();
            updateBgPreview();
        }

        // Java 桥回调 — 用户选好背景图片
        window.onBgImageLoaded = function(dataUri) {
            applyBgImageUrl(dataUri);
        };

        // — 视频 URL 输入 —
        const inputBgVideoUrl = $('#inputBgVideoUrl');
        if (inputBgVideoUrl) {
            inputBgVideoUrl.addEventListener('input', function() {
                const current = getCurrent();
                current.background.videoUrl = this.value;
                applyTheme(current);
                save();
                updateBgPreview();
            });
        }

        // — 视频文件选择 —
        $('#btnBgVideoFile').addEventListener('click', function() {
            if (window.KLink && window.KLink.pickBgVideoFile) {
                window.KLink.pickBgVideoFile();
            } else {
                $('#inputBgVideoFile').click();
            }
        });
        $('#inputBgVideoFile').addEventListener('change', function() {
            const file = this.files[0];
            if (!file) return;
            const url = URL.createObjectURL(file);
            $('#inputBgVideoUrl').value = url;
            const current = getCurrent();
            current.background.videoUrl = url;
            applyTheme(current);
            save();
            updateBgPreview();
        });

        // Java 桥回调 — 用户选好背景视频
        window.onBgVideoLoaded = function(videoUri) {
            $('#inputBgVideoUrl').value = videoUri;
            const current = getCurrent();
            current.background.videoUrl = videoUri;
            applyTheme(current);
            save();
            updateBgPreview();
        };

        // — 透明度模式 —
        $$('#bgBlendRow .option-btn').forEach(btn => {
            btn.addEventListener('click', function() {
                const blend = this.dataset.blend;
                $$('#bgBlendRow .option-btn').forEach(b => b.classList.remove('active'));
                this.classList.add('active');

                const current = getCurrent();
                current.background.blend = blend;

                // 滑块联动
                const sliderRow = $('#bgOpacitySliderRow');
                const range = $('#rangeBgOpacity');
                if (blend === 'opaque') {
                    if (sliderRow) sliderRow.style.display = 'none';
                    current.background.imageOpacity = 1;
                } else if (blend === 'frosted') {
                    if (sliderRow) sliderRow.style.display = 'none';
                    current.background.imageOpacity = 0.55;
                    current.background.imageBlur = '12px';
                } else {
                    if (sliderRow) sliderRow.style.display = 'flex';
                    if (range) {
                        current.background.imageOpacity = parseInt(range.value) / 100;
                    }
                }

                applyTheme(current);
                save();
                updateBgPreview();
            });
        });

        // — 图片不透明度滑块 —
        const rangeBgOpacity = $('#rangeBgOpacity');
        if (rangeBgOpacity) {
            rangeBgOpacity.addEventListener('input', function() {
                const val = parseInt(this.value) / 100;
                $('#valBgOpacity').textContent = this.value + '%';
                const current = getCurrent();
                current.background.imageOpacity = val;
                current.background.blend = 'normal';
                // 同步 blend 按钮
                $$('#bgBlendRow .option-btn').forEach(b => b.classList.remove('active'));
                const normalBtn = document.querySelector('#bgBlendRow [data-blend="normal"]');
                if (normalBtn) normalBtn.classList.add('active');
                applyTheme(current);
                save();
            });
        }

        // — 间距选择 —
        $$('#spacingRow .option-btn').forEach(btn => {
            btn.addEventListener('click', function() {
                $$('#spacingRow .option-btn').forEach(b => b.classList.remove('active'));
                this.classList.add('active');
                const current = getCurrent();
                current.spacing = this.dataset.spacing;
                applyTheme(current);
                save();
            });
        });

        // — 圆角选择 —
        $$('#radiusRow .option-btn').forEach(btn => {
            btn.addEventListener('click', function() {
                $$('#radiusRow .option-btn').forEach(b => b.classList.remove('active'));
                this.classList.add('active');
                const current = getCurrent();
                current.radius = this.dataset.radius;
                applyTheme(current);
                save();
            });
        });

        // — 噪点开关 —
        $('#toggleNoise').addEventListener('change', function() {
            const current = getCurrent();
            current.noise = this.checked;
            current.noiseOpacity = this.checked ? (parseInt($('#rangeNoiseOpacity').value) / 1000) : 0;
            applyTheme(current);
            save();
            $('#noiseOpacityRow').style.display = this.checked ? 'flex' : 'none';
        });

        // — 噪点强度 —
        $('#rangeNoiseOpacity').addEventListener('input', function() {
            const val = parseInt(this.value) / 1000;
            $('#valNoiseOpacity').textContent = val.toFixed(3);
            const current = getCurrent();
            current.noiseOpacity = val;
            applyTheme(current);
            save();
        });

        // — 导出（系统文件选择器） —
        $('#btnExportTheme').addEventListener('click', function() {
            exportTheme();
        });

        // — 分享主题 —
        const btnShareTheme = $('#btnShareTheme');
        if (btnShareTheme) {
            btnShareTheme.addEventListener('click', function() {
                shareTheme();
            });
        }

        // — 导入文件（优先 Java 桥，fallback 浏览器 file input） —
        $('#btnImportTheme').addEventListener('click', function() {
            if (!pickThemeFileViaBridge()) {
                // fallback: 浏览器 file input
                $('#inputImportFile').click();
            }
        });
        $('#inputImportFile').addEventListener('change', function() {
            const file = this.files[0];
            if (!file) return;
            importFromFile(file).then(() => {
                refreshUI();
            }).catch(err => {
                alert('导入失败: ' + err.message);
            });
        });

        // — 粘贴 JSON 导入 —
        $('#btnApplyPasted').addEventListener('click', function() {
            const jsonStr = $('#inputImportJson').value.trim();
            if (!jsonStr) return;
            try {
                importTheme(jsonStr);
                refreshUI();
                $('#inputImportJson').value = '';
                this.textContent = '应用成功';
                setTimeout(() => { this.textContent = '应用粘贴的主题'; }, 1500);
            } catch (e) {
                alert('导入失败: ' + e.message);
            }
        });

        // — 恢复默认 —
        $('#btnResetTheme').addEventListener('click', function() {
            if (confirm('恢复默认主题？当前修改将丢失。')) {
                reset();
                refreshUI();
            }
        });

        // 暴露刷新钩子供 Java 回调使用
        window.__themeRefreshUI = refreshUI;

        // 初始刷新
        refreshUI();
    }

    function escAttr(str) {
        if (!str) return '';
        return String(str).replace(/&/g,'&amp;').replace(/"/g,'&quot;').replace(/</g,'&lt;').replace(/>/g,'&gt;');
    }

    // ======================== 公开 API ========================
    return {
        init,
        bindUI,
        applyTheme,
        applyPreset,
        getPresets,
        exportTheme,
        shareTheme,
        importTheme,
        importFromFile,
        pickThemeFileViaBridge,
        reset,
        getCurrent,
        getColor,
        save,
        DEFAULT: DEFAULT_THEME,
        PRESETS
    };
})();
