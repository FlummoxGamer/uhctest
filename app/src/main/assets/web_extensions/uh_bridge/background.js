const api = typeof browser !== "undefined" ? browser : chrome;

let nativePort = null;

function ensureNativePort() {
    if (nativePort) return nativePort;
    try {
        nativePort = api.runtime.connectNative("uhApp");
        nativePort.onMessage.addListener((msg) => {
            if (!msg || !msg.action) return;
            api.tabs.query({ active: true }).then((tabs) => {
                if (!tabs || !tabs.length) return;
                for (const t of tabs) {
                    if (msg.action === "APPEARANCE_UPDATE") {
                        api.tabs.sendMessage(t.id, msg).catch(() => {});
                    } else if (msg.action === "ERUDA_TOGGLE") {
                        if (msg.enable && msg.code) {
                            api.tabs.executeScript(t.id, {
                                code: msg.code + "\n;try{if(window.eruda&&!window.__uh_eruda_inited){window.eruda.init();window.__uh_eruda_inited=true;}if(window.eruda){window.eruda.show();}}catch(e){}",
                                runAt: "document_idle"
                            }).catch(() => {
                                api.tabs.sendMessage(t.id, msg).catch(() => {});
                            });
                        } else {
                            api.tabs.sendMessage(t.id, msg).catch(() => {});
                        }
                    } else {
                        api.tabs.sendMessage(t.id, msg).catch(() => {});
                    }
                }
            }).catch(() => {});
        });
        nativePort.onDisconnect.addListener(() => {
            nativePort = null;
        });
    } catch (_e) {
        nativePort = null;
    }
    return nativePort;
}

ensureNativePort();

api.runtime.onMessage.addListener((message, sender, sendResponse) => {
    if (!message) return false;
    if (message.type === "UH_GET_SCRIPTS") {
        api.runtime.sendNativeMessage("uhApp", {
            type: "GET_SCRIPTS",
            url: message.url || "",
            isTop: message.isTop !== false
        }).then((response) => sendResponse(response))
          .catch(() => sendResponse(null));
        return true;
    }
    if (message.type === "UH_EXEC_CSP_FALLBACK") {
        // Fallback path if strict CSP ever blocks page-world <script> execution: run directly inside WebExtension sandbox.
        const tabId = sender && sender.tab && sender.tab.id;
        if (!tabId || !message.code) {
            sendResponse({ ok: false });
            return false;
        }
        api.tabs.executeScript(tabId, {
            code: message.code,
            allFrames: message.allFrames === true,
            runAt: message.runAt || "document_end"
        }).then(() => sendResponse({ ok: true }))
          .catch((e) => sendResponse({ ok: false, error: String(e) }));
        return true;
    }
    if (message.type === "UH_NATIVE_CALL") {
        const payload = Object.assign({}, message.payload || {}, {
            pageUrl: message.pageUrl || ""
        });
        api.runtime.sendNativeMessage("uhApp", payload)
            .then((response) => sendResponse(response))
            .catch(() => sendResponse(null));
        return true;
    }
    return false;
});
