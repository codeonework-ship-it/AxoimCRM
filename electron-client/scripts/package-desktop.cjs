"use strict";

const fs = require("node:fs");
const path = require("node:path");
const { execFileSync } = require("node:child_process");

const root = path.resolve(__dirname, "..", "..");
const electronRoot = path.join(root, "electron-client");
const packageJson = require(path.join(electronRoot, "package.json"));
const electronDist = path.join(electronRoot, "node_modules", "electron", "dist");
const frontendDist = path.join(root, "frontend", "dist");
const releaseRoot = path.join(electronRoot, "release");
const appName = "AxiomCRM";
const target = path.join(releaseRoot, `${appName}-win-x64-${packageJson.version}`);
const zipPath = `${target}.zip`;

function assertExists(location, hint) {
  if (!fs.existsSync(location)) {
    throw new Error(`${location} does not exist. ${hint}`);
  }
}

function copyDir(source, destination) {
  fs.cpSync(source, destination, { recursive: true });
}

assertExists(electronDist, "Run npm install in electron-client first.");
assertExists(frontendDist, "Run npm run build in frontend first.");

fs.mkdirSync(releaseRoot, { recursive: true });
fs.rmSync(target, { recursive: true, force: true });
fs.rmSync(zipPath, { force: true });

copyDir(electronDist, target);

const resources = path.join(target, "resources");
const appDir = path.join(resources, "app");
fs.mkdirSync(appDir, { recursive: true });
fs.copyFileSync(path.join(electronRoot, "main.js"), path.join(appDir, "main.js"));
fs.copyFileSync(path.join(electronRoot, "preload.cjs"), path.join(appDir, "preload.cjs"));
fs.writeFileSync(path.join(appDir, "package.json"), JSON.stringify({
  name: packageJson.name,
  version: packageJson.version,
  description: packageJson.description,
  main: "main.js",
  private: true,
}, null, 2));

copyDir(frontendDist, path.join(resources, "frontend", "dist"));
fs.writeFileSync(path.join(target, "AXIOM_DESKTOP_RELEASE.json"), JSON.stringify({
  app: "Axiom CRM Desktop",
  version: packageJson.version,
  createdAt: new Date().toISOString(),
  webMode: "bundled-static",
  entrypoint: "AxiomCRM.exe",
}, null, 2));

const electronExe = path.join(target, "electron.exe");
const axiomExe = path.join(target, "AxiomCRM.exe");
if (fs.existsSync(electronExe)) fs.renameSync(electronExe, axiomExe);

if (process.platform === "win32") {
  execFileSync("powershell", [
    "-NoProfile",
    "-Command",
    "& { param($source, $destination) Compress-Archive -Path $source -DestinationPath $destination -Force }",
    target,
    zipPath,
  ], { stdio: "inherit" });
} else {
  console.warn("Zip packaging is only automated on Windows; portable folder was created.");
}

console.log(`Packaged ${appName} desktop: ${target}`);
if (fs.existsSync(zipPath)) console.log(`Published local zip: ${zipPath}`);
