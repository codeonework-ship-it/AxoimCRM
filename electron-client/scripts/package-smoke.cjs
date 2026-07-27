"use strict";

const path = require("node:path");
const { app, BrowserWindow } = require("electron");

const root = path.resolve(__dirname, "..", "..");
const version = require(path.join(root, "electron-client", "package.json")).version;
const releaseIndex = path.join(
  root,
  "electron-client",
  "release",
  `AxiomCRM-win-x64-${version}`,
  "resources",
  "frontend",
  "dist",
  "index.html",
);

async function run() {
  const errors = [];
  const window = new BrowserWindow({
    show: false,
    width: 1280,
    height: 800,
    webPreferences: {
      preload: path.join(root, "electron-client", "preload.cjs"),
      contextIsolation: true,
      nodeIntegration: false,
      sandbox: true,
      backgroundThrottling: false,
    },
  });

  window.webContents.on("console-message", (_event, level, message) => {
    if (level >= 2) errors.push(message);
  });

  await window.loadFile(releaseIndex);
  const deadline = Date.now() + 10_000;
  let state;
  do {
    state = await window.webContents.executeJavaScript(`(() => ({
      title: document.title,
      rootChildren: document.querySelector("#root")?.childElementCount ?? 0,
      text: (document.body?.innerText || "").trim().replace(/\\s+/g, " ").slice(0, 180),
      location: location.href,
      desktopBridge: Boolean(window.axiomDesktop),
    }))()`);
    if (state.rootChildren > 0 && state.text) break;
    await new Promise((resolve) => setTimeout(resolve, 200));
  } while (Date.now() < deadline);

  const fatalErrors = errors.filter((message) =>
    /failed to load resource|uncaught|syntaxerror|referenceerror|typeerror/i.test(message));
  const passed = state.title === "Axiom 1.0"
    && state.rootChildren > 0
    && Boolean(state.text)
    && state.desktopBridge
    && fatalErrors.length === 0;

  console.log(JSON.stringify({ passed, state, fatalErrors }, null, 2));
  window.destroy();
  app.exit(passed ? 0 : 1);
}

app.whenReady().then(run).catch((error) => {
  console.error(error);
  app.exit(1);
});
