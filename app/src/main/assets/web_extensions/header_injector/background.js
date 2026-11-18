const api = typeof browser !== "undefined" ? browser : chrome;

let cspBypassPatterns = [];
let dntEnabled = false;
let gpcEnabled = false;
let headerPort = null;

function ensureHeaderPort() {
    if (headerPort) return headerPort;
    try {
        headerPort = api.runtime.connectNative("uhApp");
        headerPort.onMessage.addListener((msg) => {
            if (!msg) return;
            if (msg.action === "UPDATE_CONFIG") {
                if (Array.isArray(msg.cspBypassPatterns)) cspBypassPatterns = msg.cspBypassPatterns;
                dntEnabled = !!msg.dntEnabled;
                gpcEnabled = !!msg.gpcEnabled;
            }
        });
        headerPort.onDisconnect.addListener(() => {
            headerPort = null;
        });
    } catch (_e) {
        headerPort = null;
    }
    return headerPort;
}

ensureHeaderPort();

function globMatch(pattern, url) {
    if (!pattern) return false;
    if (pattern === "*" || pattern === "<all_urls>") return true;
    let pi = 0, ui = 0, star = -1, mark = 0;
    while (ui < url.length) {
        if (pi < pattern.length && pattern[pi] === url[ui]) {
            pi++; ui++;
        } else if (pi < pattern.length && pattern[pi] === "*") {
            star = pi++;
            mark = ui;
        } else if (star >= 0) {
            pi = star + 1;
            ui = ++mark;
        } else {
            return false;
        }
    }
    while (pi < pattern.length && pattern[pi] === "*") pi++;
    return pi === pattern.length;
}

function shouldRelaxCsp(url) {
    if (!url || !cspBypassPatterns.length) return false;
    for (let i = 0; i < cspBypassPatterns.length; i++) {
        if (globMatch(cspBypassPatterns[i], url)) return true;
    }
    return false;
}

function mapFetchDest(type) {
    switch (type) {
        case "main_frame": return "document";
        case "sub_frame": return "iframe";
        case "stylesheet": return "style";
        case "script": return "script";
        case "image": return "image";
        case "font": return "font";
        case "media": return "video";
        case "websocket": return "websocket";
        default: return "empty";
    }
}

function mapFetchMode(type) {
    if (type === "main_frame" || type === "sub_frame") return "navigate";
    if (type === "image" || type === "stylesheet" || type === "font" || type === "media") return "no-cors";
    return "cors";
}

api.webRequest.onBeforeSendHeaders.addListener(
    function (details) {
        const headers = details.requestHeaders || [];
        const hasSecFetch = headers.some((h) => h.name && h.name.toLowerCase() === "sec-fetch-mode");

        if (!hasSecFetch) {
            const dest = mapFetchDest(details.type);
            const mode = mapFetchMode(details.type);
            headers.push({ name: "Sec-Fetch-Dest", value: dest });
            headers.push({ name: "Sec-Fetch-Mode", value: mode });
            headers.push({ name: "Sec-Fetch-Site", value: details.type === "main_frame" ? "none" : "same-site" });
            if (details.type === "main_frame") {
                headers.push({ name: "Sec-Fetch-User", value: "?1" });
                headers.push({ name: "Upgrade-Insecure-Requests", value: "1" });
            }
            headers.push({ name: "Sec-CH-UA", value: '"Chromium";v="134", "Not:A-Brand";v="24"' });
            headers.push({ name: "Sec-CH-UA-Mobile", value: "?1" });
            headers.push({ name: "Sec-CH-UA-Platform", value: '"Android"' });
        }
        if (dntEnabled && !headers.some((h) => h.name && h.name.toLowerCase() === "dnt")) {
            headers.push({ name: "DNT", value: "1" });
        }
        if (gpcEnabled && !headers.some((h) => h.name && h.name.toLowerCase() === "sec-gpc")) {
            headers.push({ name: "Sec-GPC", value: "1" });
        }

        if (details.type === "main_frame" || details.type === "xmlhttprequest" || details.type === "sub_frame") {
            try {
                const headerMap = {};
                for (const h of headers) {
                    if (h.name) headerMap[h.name] = h.value || "";
                }
                api.runtime.sendNativeMessage("uhApp", {
                    type: "HEADER_EVENT",
                    url: details.url,
                    method: details.method || "GET",
                    reqType: details.type,
                    headers: headerMap
                }).catch(() => {});
            } catch (_e) {}
        }

        return { requestHeaders: headers };
    },
    { urls: ["<all_urls>"] },
    ["blocking", "requestHeaders"]
);

api.webRequest.onHeadersReceived.addListener(
    function (details) {
        if ((details.type !== "main_frame" && details.type !== "sub_frame") || !shouldRelaxCsp(details.url)) {
            return {};
        }
        const filtered = (details.responseHeaders || []).filter((h) => {
            const n = (h.name || "").toLowerCase();
            return n !== "content-security-policy" && n !== "content-security-policy-report-only";
        });
        return { responseHeaders: filtered };
    },
    { urls: ["<all_urls>"] },
    ["blocking", "responseHeaders"]
);
