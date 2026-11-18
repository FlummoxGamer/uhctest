const api = typeof browser !== "undefined" ? browser : chrome;

const isTopFrame = (function () {
    try { return window.self === window.top; } catch (_e) { return true; }
})();

const pendingPackets = [];
let injectReady = false;
let lastBootPayload = "";

function dispatchRunPacket(packet) {
    if (!packet) return;
    if (!injectReady) {
        pendingPackets.push(packet);
        return;
    }
    const targetRunAt = packet.runAt || "document-start";
    const runNow = () => {
        window.postMessage({ type: "UH_RUN_PACKET", packet: packet }, "*");
    };
    if (targetRunAt === "document-start") {
        runNow();
    } else if (targetRunAt === "document-body") {
        if (document.body) {
            runNow();
        } else {
            const obs = new MutationObserver(() => {
                if (document.body) {
                    obs.disconnect();
                    runNow();
                }
            });
            obs.observe(document.documentElement || document, { childList: true, subtree: true });
        }
    } else if (targetRunAt === "document-end") {
        if (document.readyState === "interactive" || document.readyState === "complete") {
            runNow();
        } else {
            document.addEventListener("DOMContentLoaded", runNow, { once: true });
        }
    } else {
        if (document.readyState === "complete") {
            setTimeout(runNow, 0);
        } else {
            window.addEventListener("load", () => setTimeout(runNow, 0), { once: true });
        }
    }
}

window.addEventListener("message", (event) => {
    if (!event.data) return;
    if (event.data.type === "UH_INJECT_READY") {
        injectReady = true;
        api.runtime.sendMessage({
            type: "UH_GET_SCRIPTS",
            url: window.location.href,
            isTop: isTopFrame
        }).then((response) => {
            if (!response) return;
            if (response.appearance) {
                window.postMessage({ type: "UH_APPEARANCE_UPDATE", appearance: response.appearance }, "*");
            }
            if (response.privacy) {
                window.postMessage({ type: "UH_PRIVACY_BOOT", privacy: response.privacy }, "*");
            }
            if (response.bootPayload) {
                lastBootPayload = response.bootPayload;
                window.postMessage({ type: "UH_BOOT_DATA", bootPayload: response.bootPayload }, "*");
            }
            if (Array.isArray(response.packets)) {
                for (const p of response.packets) {
                    dispatchRunPacket(p);
                }
            } else if (response.scripts) {
                window.postMessage({ type: "UH_RUN_SCRIPTS", scripts: response.scripts }, "*");
            }
            while (pendingPackets.length > 0) {
                dispatchRunPacket(pendingPackets.shift());
            }
        }).catch(() => {});
    } else if (event.data.type === "UH_CSP_FALLBACK" && event.data.code) {
        const execCode = (lastBootPayload ? (lastBootPayload + "\n") : "") + event.data.code;
        api.runtime.sendMessage({
            type: "UH_EXEC_CSP_FALLBACK",
            code: execCode,
            allFrames: !isTopFrame,
            runAt: "document_end"
        }).catch(() => {});
    } else if (event.data.type === "UH_NATIVE_CALL") {
        api.runtime.sendMessage({
            type: "UH_NATIVE_CALL",
            pageUrl: window.location.href,
            payload: event.data.payload
        }).then((response) => {
            if (event.data.payload && event.data.payload.id) {
                window.postMessage({
                    type: "UH_NATIVE_RESPONSE",
                    id: event.data.payload.id,
                    response: response
                }, "*");
            }
        }).catch(() => {});
    }
});

api.runtime.onMessage.addListener((msg) => {
    if (!msg || !msg.action) return;
    if (msg.action === "RUN_MENU_COMMAND" && msg.commandId) {
        window.postMessage({ type: "UH_RUN_MENU_COMMAND", commandId: msg.commandId }, "*");
    } else if (msg.action === "RELOAD_SCRIPTS") {
        api.runtime.sendMessage({
            type: "UH_GET_SCRIPTS",
            url: window.location.href,
            isTop: isTopFrame
        }).then((response) => {
            if (response && response.bootPayload) {
                lastBootPayload = response.bootPayload;
                window.postMessage({ type: "UH_BOOT_DATA", bootPayload: response.bootPayload }, "*");
            }
            if (response && Array.isArray(response.packets)) {
                for (const p of response.packets) dispatchRunPacket(p);
            }
        }).catch(() => {});
    } else if (msg.action === "APPEARANCE_UPDATE") {
        window.postMessage({
            type: "UH_APPEARANCE_UPDATE",
            appearance: { enhanceDark: !!msg.enhanceDark, textScale: msg.textScale || 100 }
        }, "*");
    } else if (msg.action === "PRIVACY_UPDATE") {
        window.postMessage({
            type: "UH_PRIVACY_BOOT",
            privacy: {
                antiFingerprint: !!msg.antiFingerprint,
                deamp: !!msg.deamp,
                dnt: !!msg.dnt,
                gpc: !!msg.gpc,
                webrtcPolicy: Number(msg.webrtcPolicy || 0)
            }
        }, "*");
    } else if (msg.action === "TRANSLATE_PAGE") {
        window.postMessage({ type: "UH_TRANSLATE_PAGE", targetLang: msg.targetLang || "en" }, "*");
    } else if (msg.action === "RESTORE_TRANSLATE") {
        window.postMessage({ type: "UH_RESTORE_TRANSLATE" }, "*");
    } else if (msg.action === "DEVTOOLS_CMD") {
        window.postMessage({ type: "UH_DEVTOOLS_CMD", command: msg.command }, "*");
    } else if (msg.action === "DEVTOOLS_EVAL") {
        window.postMessage({ type: "UH_DEVTOOLS_EVAL", code: msg.code }, "*");
    } else if (msg.action === "INSPECT_DOM") {
        window.postMessage({ type: "UH_INSPECT_DOM", selector: msg.selector || "" }, "*");
    } else if (msg.action === "APPLY_ELEMENT_STYLE") {
        window.postMessage({ type: "UH_APPLY_ELEMENT_STYLE", selector: msg.selector, css: msg.css }, "*");
    } else if (msg.action === "SET_STORAGE_ITEM") {
        window.postMessage({ type: "UH_SET_STORAGE_ITEM", kind: msg.kind, key: msg.key, value: msg.value }, "*");
    } else if (msg.action === "REMOVE_STORAGE_ITEM") {
        window.postMessage({ type: "UH_REMOVE_STORAGE_ITEM", kind: msg.kind, key: msg.key }, "*");
    } else if (msg.action === "ERUDA_TOGGLE") {
        window.postMessage({ type: "UH_ERUDA_TOGGLE", enable: !!msg.enable, code: msg.code || "" }, "*");
    }
});

const injectScript = document.createElement("script");
injectScript.src = api.runtime.getURL("inject.js");
(document.head || document.documentElement).appendChild(injectScript);
injectScript.remove();
