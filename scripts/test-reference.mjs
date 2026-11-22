// Exercise the installed XHTML with a real browser in offline mode, including MathJax's deferred loads.
import assert from "node:assert/strict";
import {spawn} from "node:child_process";
import {existsSync} from "node:fs";
import {readFile, readdir, writeFile} from "node:fs/promises";
import path from "node:path";
import {setTimeout as delay} from "node:timers/promises";
import {fileURLToPath, pathToFileURL} from "node:url";

const [library, profile, report, ...selected] = process.argv.slice(2);
const executable = [
    process.env.GLSL_TEST_BROWSER,
    path.join(process.env.ProgramFiles, "Google/Chrome/Application/chrome.exe"),
    path.join(process.env["ProgramFiles(x86)"], "Microsoft/Edge/Application/msedge.exe")
].find(file => file && existsSync(file));
assert.ok(executable, "Set GLSL_TEST_BROWSER to a Chromium browser executable");
const browser = spawn(executable, [
    "--headless", "--disable-background-networking", "--disable-component-update",
    "--disable-extensions", "--no-first-run", "--no-default-browser-check",
    "--remote-debugging-address=127.0.0.1", "--remote-debugging-port=0",
    "--user-data-dir=" + profile, "about:blank"
], {windowsHide: true, stdio: "ignore"});
let socket;
const finished = new Promise(resolve => browser.once("exit", resolve));
const pending = new Map(), urls = new Map(), files = new Set(), errors = [], messages = [];
let requestId = 0;

function send(method, params = {}) {
    const id = ++requestId;
    return new Promise((resolve, reject) => {
        const timer = setTimeout(() => {
            pending.delete(id);
            reject(Error("Timed out calling " + method));
        }, 30000);
        pending.set(id, message => {
            clearTimeout(timer);
            if (message.error) reject(Error(message.error.message));
            else resolve(message.result);
        });
        socket.send(JSON.stringify({id, method, params}));
    });
}

async function evaluate(expression) {
    const result = await send("Runtime.evaluate", {expression, awaitPromise: true, returnByValue: true});
    if (result.exceptionDetails) throw Error(result.exceptionDetails.exception?.description || result.exceptionDetails.text);
    return result.result.value;
}

async function connect() {
    let targets;
    for (let i = 0; i < 150 && !targets; i++) {
        assert.equal(browser.exitCode, null, "The browser exited before opening the test tab");
        try {
            const port = (await readFile(path.join(profile, "DevToolsActivePort"), "utf8")).split(/\s/)[0];
            targets = await (await fetch("http://127.0.0.1:" + port + "/json/list")).json();
            if (!targets.some(target => target.type === "page")) targets = null;
        } catch {}
        if (!targets) await delay(100);
    }
    assert.ok(targets, "Could not connect to the isolated browser test profile");
    socket = new WebSocket(targets.find(target => target.type === "page").webSocketDebuggerUrl);
    await new Promise((resolve, reject) => {
        socket.addEventListener("open", resolve, {once: true});
        socket.addEventListener("error", reject, {once: true});
    });
    socket.addEventListener("message", event => {
        const message = JSON.parse(event.data);
        if (message.id) {
            pending.get(message.id)?.(message);
            pending.delete(message.id);
            return;
        }
        const data = message.params;
        if (message.method === "Network.requestWillBeSent") {
            const url = new URL(data.request.url);
            urls.set(data.requestId, url.href);
            if (url.protocol === "file:") {
                const relative = path.relative(library, fileURLToPath(url)).replaceAll("\\", "/");
                if (relative.startsWith("../") || path.isAbsolute(relative)) errors.push("Outside library: " + url);
                files.add(relative);
            } else if (!["about:", "data:"].includes(url.protocol)) {
                errors.push("Nonlocal request: " + url);
            }
        } else if (message.method === "Network.loadingFailed" && data.errorText !== "net::ERR_ABORTED") {
            errors.push(data.errorText + " " + urls.get(data.requestId));
        } else if (message.method === "Runtime.exceptionThrown") {
            errors.push(data.exceptionDetails.exception?.description || data.exceptionDetails.text);
        } else if (message.method === "Log.entryAdded") {
            messages.push(data.entry.text);
        }
    });
    await send("Page.enable");
    await send("Runtime.enable");
    await send("Log.enable");
    await send("Network.enable");
    await send("Network.setCacheDisabled", {cacheDisabled: true});
    await send("Network.emulateNetworkConditions", {offline: true, latency: 0, downloadThroughput: 0, uploadThroughput: 0});
}

async function waitFor(expression, page) {
    for (let i = 0; i < 150; i++) {
        if (await evaluate(expression)) return;
        assert.deepEqual(errors, [], page);
        await delay(100);
    }
    const state = await evaluate("({url:location.href, type:document.contentType, ready:document.readyState, math:window.MathJax?.isReady, version:window.MathJax?.version, scripts:[...document.scripts].map(s=>s.src)})");
    throw Error(page + " did not finish loading: " + JSON.stringify(state) + "\n" +
        [...files].slice(-10).join("\n") + "\n" + [...errors, ...messages.slice(-8)].join("\n"));
}

try {
    await connect();
    const pages = [];
    for (const name of await readdir(library)) {
        if (!name.endsWith(".xhtml")) continue;
        const source = await readFile(path.join(library, name), "utf8");
        const body = source.substring(source.indexOf("<body>"));
        const mathml = [...body.matchAll(/<(?:\w+:)?math(?:\s|>)/g)].length;
        const tex = [...body.matchAll(/\$\$[\s\S]+?\$\$|\$[^$]+\$|\\\([\s\S]+?\\\)|\\\[[\s\S]+?\\\]/g)].length;
        const count = mathml + tex;
        if (!selected.length || selected.includes(name))
            pages.push({name, count, hasScript: source.includes("mathjax/MathJax.js")});
    }
    assert.ok(pages.length);
    let equations = 0;
    for (const {name, count, hasScript} of pages) {
        const url = pathToFileURL(path.join(library, name)).href;
        const navigation = await send("Page.navigate", {url});
        assert.ok(!navigation.errorText, name + " " + navigation.errorText);
        await waitFor("location.href === " + JSON.stringify(url) + " && document.readyState === 'complete'", name);
        assert.equal(await evaluate("navigator.onLine"), false);
        if (hasScript) {
            await waitFor("window.MathJax?.isReady", name);
            const rendered = await evaluate(
                "new Promise(resolve => MathJax.Hub.Queue(() => resolve([" +
                "MathJax.Hub.getAllJax().length, document.querySelectorAll('.MathJax_SVG svg').length, " +
                "document.querySelectorAll('.MathJax_Error, .MathJax_SVG_Error').length])))");
            assert.deepEqual(rendered, [count, count, 0], name + " rendered equations");
            equations += count;
        }
        assert.deepEqual(errors, [], name);
    }
    if (!selected.length) {
        assert.ok(pages.length >= 544);
        assert.ok(equations >= 700);
        assert.ok(files.has("mathjax/extensions/MathML/content-mathml.js"), "deferred MathML converter");
        assert.ok([...files].some(name => name.includes("/SVG/fonts/")), "deferred SVG glyphs");
        const overview = pathToFileURL(path.join(library, "OpenGL46.xhtml")).href;
        await send("Page.navigate", {url: overview});
        await waitFor("location.href === " + JSON.stringify(overview) + " && !!document.querySelector('a')", "overview");
        await evaluate("document.querySelector('a[href=\"glPolygonOffsetClamp.xhtml\"]').click()");
        await waitFor("location.pathname.endsWith('/glPolygonOffsetClamp.xhtml') && window.MathJax?.isReady", "offline cross-reference");
        await evaluate("new Promise(resolve => MathJax.Ajax.Require('[MathJax]/localization/fr/fr.js', resolve))");
        assert.ok(files.has("mathjax/localization/fr/fr.js"), "on-demand language module");
        const clip = pathToFileURL(path.join(library, "glClipControl.xhtml")).href;
        await send("Page.navigate", {url: clip});
        await waitFor("location.href === " + JSON.stringify(clip) + " && !!document.querySelector('a[href^=\"specs/\"]')", "extension source");
        await evaluate("document.querySelector('a[href=\"specs/ARB_clip_control.txt\"]').click()");
        await waitFor("location.pathname.endsWith('/specs/ARB_clip_control.txt') && document.body?.innerText.includes('ARB_clip_control')", "offline extension");
        for (const name of ["glspec46.core.pdf", "GLSLangSpec.4.60.pdf"]) {
            const specification = await readFile(path.join(library, "specs", name));
            assert.equal(specification.subarray(0, 5).toString(), "%PDF-", name);
        }
    }
    assert.deepEqual(errors, []);
    const result = {pages: pages.length, equations, localFiles: files.size, externalRequests: 0};
    await writeFile(report, JSON.stringify(result, null, 2) + "\n");
    console.log("Offline browser:", JSON.stringify(result));
} finally {
    if (socket?.readyState === WebSocket.OPEN) {
        socket.send(JSON.stringify({id: ++requestId, method: "Browser.close"}));
        socket.close();
    }
    if (browser.exitCode === null) await Promise.race([finished, delay(1500)]);
    if (browser.exitCode === null) browser.kill();
}
