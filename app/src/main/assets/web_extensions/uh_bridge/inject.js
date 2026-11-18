(function () {
    if (window.__uh_bridge_inject_ready) return;
    window.__uh_bridge_inject_ready = true;

    const pendingTranslationCallbacks = {};

    function sendNative(type, payload, cb) {
        const msg = Object.assign({ type: type }, payload || {});
        if (cb) {
            const id = "cb_" + Math.random().toString(36).slice(2);
            msg.id = id;
            pendingTranslationCallbacks[id] = cb;
        }
        window.postMessage({ type: "UH_NATIVE_CALL", payload: msg }, "*");
    }

    function execCodeSafe(code, allowSandboxFallback) {
        if (!code) return;
        let blocked = false;
        const marker = "__uh_exec_" + Math.random().toString(36).slice(2);
        const onCsp = (e) => {
            if (e.violatedDirective && e.violatedDirective.indexOf("script-src") !== -1) {
                blocked = true;
            }
        };
        document.addEventListener("securitypolicyviolation", onCsp);
        try {
            const s = document.createElement("script");
            s.textContent = "window['" + marker + "']=1;\n" + code;
            (document.head || document.documentElement).appendChild(s);
            s.remove();
        } catch (_e) {
            blocked = true;
        }
        document.removeEventListener("securitypolicyviolation", onCsp);

        if (blocked || !window[marker]) {
            let ran = false;
            try {
                const fn = new Function(code);
                fn.call(window);
                ran = true;
            } catch (_e1) {}

            if (!ran) {
                try {
                    const blob = new Blob([code], { type: "application/javascript" });
                    const bUrl = URL.createObjectURL(blob);
                    const s2 = document.createElement("script");
                    s2.src = bUrl;
                    s2.onload = () => { URL.revokeObjectURL(bUrl); s2.remove(); };
                    s2.onerror = () => {
                        URL.revokeObjectURL(bUrl);
                        s2.remove();
                        if (allowSandboxFallback !== false) {
                            window.postMessage({ type: "UH_CSP_FALLBACK", code: code }, "*");
                        }
                    };
                    (document.head || document.documentElement).appendChild(s2);
                } catch (_e2) {
                    if (allowSandboxFallback !== false) {
                        window.postMessage({ type: "UH_CSP_FALLBACK", code: code }, "*");
                    }
                }
            }
        }
        try { delete window[marker]; } catch (_e) {}
    }

    // ---- Enhance Dark Mode & Live Page Zoom CSS ----
    function applyAppearance(appearance) {
        if (!appearance) return;
        const darkStyleId = "uh-enhance-dark-style";
        let darkEl = document.getElementById(darkStyleId);
        if (appearance.enhanceDark) {
            if (!darkEl) {
                darkEl = document.createElement("style");
                darkEl.id = darkStyleId;
                darkEl.textContent =
                    "html { background-color: #0b0f17 !important; color-scheme: dark !important; filter: invert(0.92) hue-rotate(180deg) contrast(0.95) !important; }\n" +
                    "img, video, picture, canvas, svg image, iframe, [style*='background-image'] { filter: invert(1) hue-rotate(180deg) !important; }\n";
                (document.head || document.documentElement).appendChild(darkEl);
            }
        } else if (darkEl) {
            darkEl.remove();
        }

        const scale = Number(appearance.textScale || 100);
        const zoomStyleId = "uh-page-zoom-style";
        let zoomEl = document.getElementById(zoomStyleId);
        if (scale !== 100 && scale >= 50 && scale <= 200) {
            const pct = scale + "%";
            const css = "html { -webkit-text-size-adjust: " + pct + " !important; text-size-adjust: " + pct + " !important; font-size: " + pct + " !important; }";
            if (!zoomEl) {
                zoomEl = document.createElement("style");
                zoomEl.id = zoomStyleId;
                (document.head || document.documentElement).appendChild(zoomEl);
            }
            zoomEl.textContent = css;
        } else if (zoomEl) {
            zoomEl.remove();
        }
    }

    // ---- Anti-Fingerprint & De-AMP Privacy Hooks ----
    function applyPrivacyHooks(privacy) {
        if (!privacy) return;
        if (privacy.deamp && /\/\/(amp\.|www\.google\.[^/]+\/amp\/|[^/]+\.cdn\.ampproject\.org)/i.test(location.href)) {
            try {
                const canonical = document.querySelector('link[rel="canonical"]');
                if (canonical && canonical.href && canonical.href !== location.href && canonical.href.startsWith("http")) {
                    location.replace(canonical.href);
                }
            } catch (_e) {}
        }
        if (privacy.antiFingerprint && !window.__uh_fp_shielded) {
            window.__uh_fp_shielded = true;
            try {
                const seed = (Math.random() * 0.0001) + 0.00001;
                const origToDataURL = HTMLCanvasElement.prototype.toDataURL;
                HTMLCanvasElement.prototype.toDataURL = function () {
                    try {
                        const ctx = this.getContext("2d");
                        if (ctx && this.width > 0 && this.height > 0) {
                            const d = ctx.getImageData(0, 0, 1, 1);
                            d.data[0] = (d.data[0] + Math.floor(seed * 1000)) & 255;
                            ctx.putImageData(d, 0, 0);
                        }
                    } catch (_e) {}
                    return origToDataURL.apply(this, arguments);
                };
                if (typeof navigator.hardwareConcurrency === "number") {
                    Object.defineProperty(navigator, "hardwareConcurrency", { get: () => 4 });
                }
                if (typeof navigator.deviceMemory === "number") {
                    Object.defineProperty(navigator, "deviceMemory", { get: () => 8 });
                }
            } catch (_e) {}
        }
        if (privacy.dnt) {
            try { Object.defineProperty(navigator, "doNotTrack", { get: () => "1", configurable: true }); } catch (_e) {}
        }
        if (privacy.gpc) {
            try { Object.defineProperty(navigator, "globalPrivacyControl", { get: () => true, configurable: true }); } catch (_e) {}
        }
        const rtcPolicy = Number(privacy.webrtcPolicy || 0);
        if (rtcPolicy === 2 && !window.__uh_rtc_blocked) {
            window.__uh_rtc_blocked = true;
            try {
                const blockedRtc = function () { throw new DOMException("WebRTC disabled by UH Privacy Guard", "NotAllowedError"); };
                window.RTCPeerConnection = blockedRtc;
                window.webkitRTCPeerConnection = blockedRtc;
                window.mozRTCPeerConnection = blockedRtc;
            } catch (_e) {}
        } else if (rtcPolicy === 1 && !window.__uh_rtc_relay && typeof window.RTCPeerConnection === "function") {
            window.__uh_rtc_relay = true;
            try {
                const OrigRtc = window.RTCPeerConnection;
                const WrappedRtc = function (cfg, constraints) {
                    const next = Object.assign({}, cfg || {}, { iceTransportPolicy: "relay" });
                    return new OrigRtc(next, constraints);
                };
                WrappedRtc.prototype = OrigRtc.prototype;
                window.RTCPeerConnection = WrappedRtc;
                window.webkitRTCPeerConnection = WrappedRtc;
            } catch (_e) {}
        }
    }

    // ---- In-Place CAPTCHA-Free DOM Translator ----
    const originalNodeTexts = new WeakMap();
    const translatedNodes = new Set();

    function collectTranslatableNodes() {
        const root = document.body || document.documentElement;
        if (!root) return [];
        const skipTags = { SCRIPT: 1, STYLE: 1, NOSCRIPT: 1, CODE: 1, PRE: 1, TEXTAREA: 1, SVG: 1 };
        const walker = document.createTreeWalker(root, NodeFilter.SHOW_TEXT, {
            acceptNode: (node) => {
                const parent = node.parentElement;
                if (!parent || skipTags[parent.tagName] || parent.isContentEditable) return NodeFilter.FILTER_REJECT;
                const txt = (node.nodeValue || "").trim();
                if (txt.length < 2 || /^[\d\s.,:/\-+()%$@#!?]+$/.test(txt)) return NodeFilter.FILTER_REJECT;
                return NodeFilter.FILTER_ACCEPT;
            }
        });
        const nodes = [];
        let cur;
        while ((cur = walker.nextNode())) {
            if (!originalNodeTexts.has(cur)) {
                originalNodeTexts.set(cur, cur.nodeValue);
            }
            nodes.push(cur);
            if (nodes.length >= 450) break;
        }
        return nodes;
    }

    function applyInPageTranslate(targetLang) {
        const nodes = collectTranslatableNodes();
        if (nodes.length === 0) {
            sendNative("TRANSLATE_STATUS", { ok: false, error: "No translatable text found" });
            return;
        }
        const rawList = nodes.map((n) => (originalNodeTexts.get(n) || n.nodeValue || "").trim());
        const uniqueMap = new Map();
        const uniqueTexts = [];
        for (const t of rawList) {
            if (!uniqueMap.has(t)) {
                uniqueMap.set(t, uniqueTexts.length);
                uniqueTexts.push(t);
            }
        }
        sendNative("TRANSLATE_BATCH", { targetLang: targetLang || "en", texts: uniqueTexts }, (resp) => {
            if (!resp || resp.error || !Array.isArray(resp.translated)) {
                sendNative("TRANSLATE_STATUS", { ok: false, error: (resp && resp.error) || "Translation failed" });
                return;
            }
            const out = resp.translated;
            nodes.forEach((node, idx) => {
                const orig = originalNodeTexts.get(node) || node.nodeValue || "";
                const key = orig.trim();
                const uIdx = uniqueMap.get(key);
                if (uIdx !== undefined && typeof out[uIdx] === "string" && out[uIdx].trim().length > 0) {
                    const leading = orig.match(/^\s*/)[0] || "";
                    const trailing = orig.match(/\s*$/)[0] || "";
                    node.nodeValue = leading + out[uIdx].trim() + trailing;
                    translatedNodes.add(node);
                }
            });
            sendNative("TRANSLATE_STATUS", { ok: true });
        });
    }

    function restoreOriginalTexts() {
        translatedNodes.forEach((node) => {
            if (originalNodeTexts.has(node)) {
                node.nodeValue = originalNodeTexts.get(node);
            }
        });
        translatedNodes.clear();
    }

    // ---- Hybrid DevTools Hooks (Console + Fetch/XHR + DOM/Storage/Perf Inspector) ----
    if (!window.__uh_devtools_hooked) {
        window.__uh_devtools_hooked = true;
        const formatArgs = (args) => Array.from(args).map((a) => {
            try {
                if (typeof a === "string") return a;
                if (a instanceof Error) return a.stack || a.message || String(a);
                return JSON.stringify(a);
            } catch (_e) { return String(a); }
        }).join(" ");

        ["log", "info", "warn", "error"].forEach((lvl) => {
            const orig = console[lvl];
            console[lvl] = function () {
                try {
                    sendNative("DEVTOOLS_CONSOLE", { level: lvl, message: formatArgs(arguments), source: "console" });
                } catch (_e) {}
                return orig.apply(console, arguments);
            };
        });

        window.addEventListener("error", (ev) => {
            sendNative("DEVTOOLS_CONSOLE", {
                level: "error",
                message: (ev.message || "Script error") + (ev.filename ? " (" + ev.filename + ":" + ev.lineno + ")" : ""),
                source: "error"
            });
        });

        window.addEventListener("unhandledrejection", (ev) => {
            sendNative("DEVTOOLS_CONSOLE", {
                level: "error",
                message: "Unhandled Promise rejection: " + String(ev.reason || ""),
                source: "promise"
            });
        });

        if (typeof window.fetch === "function") {
            const origFetch = window.fetch;
            window.fetch = function (input, init) {
                const start = performance.now();
                const url = typeof input === "string" ? input : (input && input.url) || "";
                const method = (init && init.method) || (input && input.method) || "GET";
                return origFetch.apply(this, arguments).then((resp) => {
                    const dur = Math.round(performance.now() - start);
                    try {
                        const hdrs = {};
                        resp.headers.forEach((v, k) => { hdrs[k] = v; });
                        const clone = resp.clone();
                        clone.text().then((txt) => {
                            sendNative("DEVTOOLS_NETWORK", {
                                method: method,
                                url: url,
                                status: resp.status,
                                statusText: resp.statusText || "",
                                durationMs: dur,
                                sizeBytes: txt.length,
                                reqType: "fetch",
                                respHeaders: hdrs,
                                preview: txt.slice(0, 1500)
                            });
                        }).catch(() => {});
                    } catch (_e) {}
                    return resp;
                }).catch((err) => {
                    sendNative("DEVTOOLS_NETWORK", {
                        method: method,
                        url: url,
                        status: 0,
                        statusText: String(err),
                        durationMs: Math.round(performance.now() - start),
                        reqType: "fetch"
                    });
                    throw err;
                });
            };
        }

        const OrigXhr = window.XMLHttpRequest;
        if (OrigXhr) {
            const origOpen = OrigXhr.prototype.open;
            const origSend = OrigXhr.prototype.send;
            OrigXhr.prototype.open = function (method, url) {
                this.__uh_m = method || "GET";
                this.__uh_u = String(url || "");
                return origOpen.apply(this, arguments);
            };
            OrigXhr.prototype.send = function () {
                const t0 = performance.now();
                this.addEventListener("loadend", () => {
                    try {
                        const preview = typeof this.responseText === "string" ? this.responseText.slice(0, 1500) : "";
                        sendNative("DEVTOOLS_NETWORK", {
                            method: this.__uh_m || "GET",
                            url: this.__uh_u || "",
                            status: this.status || 0,
                            statusText: this.statusText || "",
                            durationMs: Math.round(performance.now() - t0),
                            sizeBytes: preview.length,
                            reqType: "xhr",
                            preview: preview
                        });
                    } catch (_e) {}
                });
                return origSend.apply(this, arguments);
            };
        }
    }

    function sendDomSnapshot(targetSelector) {
        try {
            const nodes = [];
            const walk = (el, depth) => {
                if (!el || el.nodeType !== 1 || nodes.length >= 160) return;
                const tag = (el.tagName || "").toLowerCase();
                if (tag === "script" || tag === "style") return;
                const idAttr = el.id || "";
                const cls = typeof el.className === "string" ? el.className.trim() : "";
                let selector = tag;
                if (idAttr) selector += "#" + idAttr;
                else if (cls) selector += "." + cls.split(/\s+/)[0];
                nodes.push({
                    tag: tag,
                    id: idAttr,
                    cls: cls,
                    depth: depth,
                    children: el.children ? el.children.length : 0,
                    selector: selector,
                    text: (el.childNodes.length === 1 && el.childNodes[0].nodeType === 3) ? (el.textContent || "").trim().slice(0, 60) : ""
                });
                if (depth < 6) {
                    for (let i = 0; i < Math.min(el.children.length, 20); i++) {
                        walk(el.children[i], depth + 1);
                    }
                }
            };
            walk(document.body || document.documentElement, 0);

            const computed = {};
            let inlineStyle = "";
            if (targetSelector) {
                const target = document.querySelector(targetSelector);
                if (target) {
                    const cs = window.getComputedStyle(target);
                    ["display", "position", "width", "height", "color", "backgroundColor", "fontSize", "margin", "padding"].forEach((prop) => {
                        computed[prop] = cs.getPropertyValue(prop.replace(/[A-Z]/g, (m) => "-" + m.toLowerCase()));
                    });
                    inlineStyle = target.getAttribute("style") || "";
                }
            }

            const vp = document.querySelector('meta[name="viewport"]');
            sendNative("DEVTOOLS_DOM", {
                title: document.title || "",
                url: location.href,
                doctype: document.doctype ? document.doctype.name : "html",
                charset: document.characterSet || "UTF-8",
                viewport: vp ? vp.content : "",
                nodeCount: document.getElementsByTagName("*").length,
                nodes: nodes,
                inspectedSelector: targetSelector || "",
                computed: computed,
                inspectedInlineStyle: inlineStyle
            });
        } catch (_e) {}
    }

    function sendStorageSnapshot() {
        try {
            const readStore = (s) => {
                const out = [];
                if (!s) return out;
                for (let i = 0; i < Math.min(s.length, 150); i++) {
                    const k = s.key(i);
                    out.push({ k: k, v: String(s.getItem(k) || "") });
                }
                return out;
            };
            const cookies = (document.cookie || "").split(";").map((c) => c.trim()).filter(Boolean).map((c) => {
                const eq = c.indexOf("=");
                return eq > 0 ? { k: c.slice(0, eq), v: c.slice(eq + 1) } : { k: c, v: "" };
            });
            sendNative("DEVTOOLS_STORAGE", {
                origin: location.origin || "",
                local: readStore(window.localStorage),
                session: readStore(window.sessionStorage),
                cookies: cookies
            });
        } catch (_e) {}
    }

    function sendPerfSnapshot() {
        try {
            const nav = performance.getEntriesByType && performance.getEntriesByType("navigation")[0];
            const res = performance.getEntriesByType ? performance.getEntriesByType("resource") : [];
            const transfer = res.reduce((acc, r) => acc + (r.transferSize || 0), 0);
            const mem = performance.memory ? (performance.memory.usedJSHeapSize / (1024 * 1024)) : 0;
            sendNative("DEVTOOLS_PERF", {
                dnsMs: nav ? Math.round(nav.domainLookupEnd - nav.domainLookupStart) : 0,
                tcpMs: nav ? Math.round(nav.connectEnd - nav.connectStart) : 0,
                ttfbMs: nav ? Math.round(nav.responseStart - nav.requestStart) : 0,
                domInteractiveMs: nav ? Math.round(nav.domInteractive) : 0,
                domCompleteMs: nav ? Math.round(nav.domComplete) : 0,
                loadEventMs: nav ? Math.round(nav.loadEventEnd) : 0,
                resourceCount: res.length,
                transferKb: Math.round(transfer / 1024),
                memoryMb: mem
            });
        } catch (_e) {}
    }

    window.addEventListener("message", (event) => {
        const d = event.data;
        if (!d) return;
        if (d.type === "UH_NATIVE_RESPONSE" && d.id && pendingTranslationCallbacks[d.id]) {
            const cb = pendingTranslationCallbacks[d.id];
            delete pendingTranslationCallbacks[d.id];
            cb(d.response);
        } else if (d.type === "UH_BOOT_DATA" && d.bootPayload) {
            execCodeSafe(d.bootPayload, false);
        } else if (d.type === "UH_RUN_PACKET" && d.packet) {
            execCodeSafe(d.packet.code, true);
        } else if (d.type === "UH_RUN_SCRIPTS" && d.scripts) {
            execCodeSafe(d.scripts, true);
        } else if (d.type === "UH_APPEARANCE_UPDATE") {
            applyAppearance(d.appearance);
        } else if (d.type === "UH_PRIVACY_BOOT") {
            applyPrivacyHooks(d.privacy);
        } else if (d.type === "UH_RUN_MENU_COMMAND" && d.commandId) {
            if (typeof window.__uh_runMenuCommand === "function") {
                try { window.__uh_runMenuCommand(d.commandId); } catch (_e) {}
            }
        } else if (d.type === "UH_TRANSLATE_PAGE") {
            applyInPageTranslate(d.targetLang);
        } else if (d.type === "UH_RESTORE_TRANSLATE") {
            restoreOriginalTexts();
        } else if (d.type === "UH_DEVTOOLS_CMD") {
            if (d.command === "INSPECT_DOM") sendDomSnapshot("");
            else if (d.command === "INSPECT_STORAGE") sendStorageSnapshot();
            else if (d.command === "INSPECT_PERF") sendPerfSnapshot();
        } else if (d.type === "UH_INSPECT_DOM") {
            sendDomSnapshot(d.selector || "");
        } else if (d.type === "UH_APPLY_ELEMENT_STYLE" && d.selector) {
            try {
                const el = document.querySelector(d.selector);
                if (el) el.style.cssText += ";" + String(d.css || "");
                sendDomSnapshot(d.selector);
            } catch (_e) {}
        } else if (d.type === "UH_SET_STORAGE_ITEM" && d.key) {
            try {
                if (d.kind === "session") sessionStorage.setItem(d.key, d.value || "");
                else if (d.kind === "cookies") document.cookie = d.key + "=" + encodeURIComponent(d.value || "") + "; path=/";
                else localStorage.setItem(d.key, d.value || "");
                sendStorageSnapshot();
            } catch (_e) {}
        } else if (d.type === "UH_REMOVE_STORAGE_ITEM" && d.key) {
            try {
                if (d.kind === "session") sessionStorage.removeItem(d.key);
                else if (d.kind === "cookies") document.cookie = d.key + "=; Max-Age=0; path=/";
                else localStorage.removeItem(d.key);
                sendStorageSnapshot();
            } catch (_e) {}
        } else if (d.type === "UH_DEVTOOLS_EVAL" && d.code) {
            try {
                const res = (0, eval)(d.code);
                let formatted = "";
                try { formatted = typeof res === "string" ? res : JSON.stringify(res); } catch (_e) { formatted = String(res); }
                sendNative("DEVTOOLS_CONSOLE", { level: "result", message: String(formatted), source: "repl" });
            } catch (err) {
                sendNative("DEVTOOLS_CONSOLE", { level: "error", message: String(err && err.stack || err), source: "repl" });
            }
        } else if (d.type === "UH_ERUDA_TOGGLE") {
            if (d.enable) {
                if (!window.eruda && d.code) execCodeSafe(d.code, true);
                setTimeout(() => {
                    try {
                        if (window.eruda) {
                            if (!window.__uh_eruda_inited) { window.eruda.init(); window.__uh_eruda_inited = true; }
                            window.eruda.show();
                        }
                    } catch (_e) {}
                }, 40);
            } else {
                try { if (window.eruda) window.eruda.hide(); } catch (_e) {}
            }
        }
    });

    window.postMessage({ type: "UH_INJECT_READY" }, "*");
})();
