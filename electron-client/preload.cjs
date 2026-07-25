"use strict";

const { contextBridge, ipcRenderer } = require("electron");

/**
 * Desktop bridge for the Axiom CRM web app. The web app checks
 * `window.axiomDesktop` to detect that it is running inside the shell.
 */
contextBridge.exposeInMainWorld("axiomDesktop", {
  /**
   * Show a native OS notification.
   * @param {string} title
   * @param {string} body
   */
  notify(title, body) {
    ipcRenderer.send("axiom:notify", { title, body });
  },
  platform: process.platform,
  versions: {
    electron: process.versions.electron,
    chrome: process.versions.chrome,
    node: process.versions.node,
  },
});
