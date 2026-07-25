"use strict";

const path = require("node:path");
const {
  app,
  BrowserWindow,
  ipcMain,
  Menu,
  Notification,
  shell,
} = require("electron");

const IS_DEV = process.env.ELECTRON_DEV === "1";
const DEV_URL = "http://localhost:5173";
const HOSTED_URL = process.env.AXIOM_WEB_URL;
const PROD_INDEX = app.isPackaged
  ? path.join(process.resourcesPath, "frontend", "dist", "index.html")
  : path.join(__dirname, "..", "frontend", "dist", "index.html");

/** @type {BrowserWindow | null} */
let mainWindow = null;

function createWindow() {
  mainWindow = new BrowserWindow({
    width: 1440,
    height: 900,
    minWidth: 1024,
    minHeight: 700,
    backgroundColor: "#0A0D14",
    show: false,
    webPreferences: {
      preload: path.join(__dirname, "preload.cjs"),
      contextIsolation: true,
      nodeIntegration: false,
      sandbox: true,
    },
  });

  mainWindow.once("ready-to-show", () => mainWindow?.show());

  // External links open in the default browser, never inside the shell.
  mainWindow.webContents.setWindowOpenHandler(({ url }) => {
    shell.openExternal(url);
    return { action: "deny" };
  });

  if (HOSTED_URL) {
    mainWindow.loadURL(HOSTED_URL);
  } else if (IS_DEV) {
    mainWindow.loadURL(DEV_URL);
  } else {
    mainWindow.loadFile(PROD_INDEX);
  }

  mainWindow.on("closed", () => {
    mainWindow = null;
  });
}

function buildMenu() {
  const isMac = process.platform === "darwin";

  /** @type {Electron.MenuItemConstructorOptions[]} */
  const template = [
    ...(isMac ? [{ role: "appMenu" }] : []),
    {
      label: "File",
      submenu: [isMac ? { role: "close" } : { role: "quit" }],
    },
    { role: "editMenu" },
    {
      label: "View",
      submenu: [
        { role: "reload" },
        { role: "forceReload" },
        { role: "toggleDevTools" },
        { type: "separator" },
        { role: "resetZoom" },
        { role: "zoomIn" },
        { role: "zoomOut" },
        { type: "separator" },
        { role: "togglefullscreen" },
      ],
    },
    { role: "windowMenu" },
  ];

  Menu.setApplicationMenu(Menu.buildFromTemplate(template));
}

// Native OS notifications requested by the web app via the preload bridge.
ipcMain.on("axiom:notify", (_event, payload) => {
  if (!Notification.isSupported()) return;
  const title = String(payload?.title ?? "Axiom CRM").slice(0, 120);
  const body = String(payload?.body ?? "").slice(0, 400);
  new Notification({ title, body }).show();
});

app.whenReady().then(() => {
  if (process.platform === "win32") {
    app.setAppUserModelId("com.axiom.crm"); // required for Windows toasts
  }
  buildMenu();
  createWindow();

  app.on("activate", () => {
    if (BrowserWindow.getAllWindows().length === 0) createWindow();
  });
});

app.on("window-all-closed", () => {
  if (process.platform !== "darwin") app.quit();
});
