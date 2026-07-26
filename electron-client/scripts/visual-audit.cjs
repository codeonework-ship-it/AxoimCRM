"use strict";

/**
 * Electron viewport acceptance audit.
 *
 * It opens the same hardened renderer configuration as the desktop client,
 * signs into a local integrated stack, visits representative high-density
 * routes and writes both screenshots and a machine-readable audit report.
 * Any page-level overflow, clipped interactive label, off-screen control or
 * upscaled raster image fails the command.
 */

const fs = require("node:fs");
const path = require("node:path");
const { app, BrowserWindow } = require("electron");

const root = path.resolve(__dirname, "..", "..");
const outputRoot = path.join(root, "electron-client", "audit-output");
const baseUrl = (process.env.AXIOM_AUDIT_URL || "http://localhost:4280").replace(/\/$/, "");
const apiUrl = (process.env.AXIOM_AUDIT_API_URL || "http://localhost:8080/api/v1").replace(/\/$/, "");
const credentials = {
  tenantSlug: process.env.AXIOM_AUDIT_TENANT || "meridian",
  email: process.env.AXIOM_AUDIT_EMAIL || "superadmin@axiomcrm.com",
  password: process.env.AXIOM_AUDIT_PASSWORD || "axiom-demo",
};
const viewports = [
  { name: "minimum", width: 1024, height: 700 },
  { name: "laptop", width: 1280, height: 800 },
  { name: "desktop", width: 1440, height: 900 },
  { name: "full-hd", width: 1920, height: 1080 },
];
const routes = [
  { name: "home", path: "/", heading: "Good day" },
  { name: "contacts", path: "/contacts", heading: "Contacts" },
  { name: "rbac", path: "/security/authorization", heading: "Authorization" },
  { name: "reports", path: "/reports", heading: "CRM Reports" },
];

function safeName(value) {
  return value.replace(/[^a-z0-9-]+/gi, "-").toLowerCase();
}

async function inspectPage(window) {
  return window.webContents.executeJavaScript(`(() => {
    const visible = (element) => {
      const style = getComputedStyle(element);
      const rect = element.getBoundingClientRect();
      return style.display !== "none" && style.visibility !== "hidden" && rect.width > 0 && rect.height > 0;
    };
    const describe = (element) => ({
      tag: element.tagName.toLowerCase(),
      text: (element.getAttribute("aria-label") || element.textContent || "").trim().replace(/\\s+/g, " ").slice(0, 100),
      className: typeof element.className === "string" ? element.className.slice(0, 120) : "",
    });
    const interactive = [...document.querySelectorAll("button, a, input, select, textarea, [role=button], [role=tab]")]
      .filter(visible);
    const clippedControls = interactive.filter((element) => {
      const style = getComputedStyle(element);
      return element.scrollWidth > element.clientWidth + 2
        && ["hidden", "clip"].includes(style.overflowX)
        && !element.matches("[data-allow-truncation=true]");
    }).map(describe);
    const insideHorizontalScroller = (element) => {
      for (let current = element.parentElement; current && current !== document.body; current = current.parentElement) {
        const overflow = getComputedStyle(current).overflowX;
        if (["auto", "scroll"].includes(overflow)) return true;
      }
      return false;
    };
    const offscreenControls = interactive.filter((element) => {
      const rect = element.getBoundingClientRect();
      return (rect.left < -1 || rect.right > innerWidth + 1) && !insideHorizontalScroller(element);
    }).map(describe);
    const pixelatedImages = [...document.images].filter((element) => {
      if (!visible(element) || !element.complete || !element.naturalWidth) return false;
      if (element.src.endsWith(".svg") || element.src.startsWith("data:image/svg")) return false;
      const rect = element.getBoundingClientRect();
      return rect.width * devicePixelRatio > element.naturalWidth + 2
        || rect.height * devicePixelRatio > element.naturalHeight + 2;
    }).map((element) => ({ ...describe(element), src: element.currentSrc.slice(0, 160) }));
    const collapsedReportLibrary = document.querySelector(".report-library.is-collapsed");
    const reportLibraryList = collapsedReportLibrary?.querySelector(".report-library-list");
    const reportRail = !reportLibraryList ? null : (() => {
      const listRect = reportLibraryList.getBoundingClientRect();
      const marks = [...reportLibraryList.querySelectorAll(".report-library-mark")];
      const clippedMarks = marks.filter((mark) => {
        const rect = mark.getBoundingClientRect();
        return rect.left < listRect.left - 1 || rect.right > listRect.right + 1;
      }).map(describe);
      return {
        railWidth: collapsedReportLibrary.getBoundingClientRect().width,
        listClientWidth: reportLibraryList.clientWidth,
        listScrollWidth: reportLibraryList.scrollWidth,
        horizontalOverflow: reportLibraryList.scrollWidth > reportLibraryList.clientWidth,
        clippedMarks,
      };
    })();
    return {
      title: document.title,
      url: location.href,
      heading: document.querySelector("h1")?.textContent?.trim().replace(/\\s+/g, " ") || null,
      viewport: { width: innerWidth, height: innerHeight, devicePixelRatio },
      documentOverflowX: Math.max(document.documentElement.scrollWidth, document.body.scrollWidth) > innerWidth + 2
        && !["hidden", "clip"].includes(getComputedStyle(document.documentElement).overflowX)
        && !["hidden", "clip"].includes(getComputedStyle(document.body).overflowX),
      clippedControls,
      offscreenControls,
      pixelatedImages,
      reportRail,
    };
  })()`);
}

async function signIn(window) {
  const response = await fetch(`${apiUrl}/auth/login`, {
    method: "POST",
    headers: { "Content-Type": "application/json" },
    body: JSON.stringify(credentials),
  });
  if (!response.ok) throw new Error(`Audit sign-in failed (${response.status})`);
  const session = await response.json();
  await window.webContents.executeJavaScript(
    `localStorage.setItem("axiom.session", ${JSON.stringify(JSON.stringify(session))})`,
  );
}

async function navigate(window, route) {
  // preload.cjs identifies this as the desktop shell, so the frontend selects
  // HashRouter. Exercise its real route contract instead of the browser URL.
  await window.loadURL(`${baseUrl}/#${route.path}`);
  const deadline = Date.now() + 10_000;
  while (Date.now() < deadline) {
    const ready = await window.webContents.executeJavaScript(`(() => {
      const text = document.body?.innerText || "";
      return document.readyState === "complete"
        && location.hash === ${JSON.stringify(`#${route.path}`)}
        && (document.querySelector("h1")?.textContent || "").includes(${JSON.stringify(route.heading)})
        && !text.includes("LOADING AXIOM WORKSPACE")
        && !text.includes("Preparing the selected module");
    })()`);
    if (ready) break;
    await new Promise((resolve) => setTimeout(resolve, 200));
  }
  // Let final layout, fonts and query-driven rows settle before measuring.
  await new Promise((resolve) => setTimeout(resolve, 500));
}

async function run() {
  fs.rmSync(outputRoot, { recursive: true, force: true });
  fs.mkdirSync(outputRoot, { recursive: true });

  const window = new BrowserWindow({
    show: false,
    width: 1024,
    height: 700,
    useContentSize: true,
    backgroundColor: "#0A0D14",
    webPreferences: {
      preload: path.join(root, "electron-client", "preload.cjs"),
      contextIsolation: true,
      nodeIntegration: false,
      sandbox: true,
      backgroundThrottling: false,
      offscreen: true,
    },
  });
  let lastPaint = null;
  window.webContents.setFrameRate(30);
  window.webContents.on("paint", (_event, _dirty, image) => {
    lastPaint = image;
  });
  window.webContents.setZoomFactor(1);
  await navigate(window, { path: "/login" });
  await signIn(window);

  const results = [];
  for (const viewport of viewports) {
    window.setContentSize(viewport.width, viewport.height);
    for (const route of routes) {
      await navigate(window, route);
      if (route.name === "reports") {
        await window.webContents.executeJavaScript(`(() => {
          const toggle = document.querySelector(".report-library-toggle[aria-expanded=true]");
          if (toggle) toggle.click();
        })()`);
      }
      const audit = await inspectPage(window);
      const file = `${safeName(viewport.name)}-${safeName(route.name)}.png`;
      await window.webContents.executeJavaScript("new Promise(resolve => requestAnimationFrame(() => requestAnimationFrame(resolve)))");
      lastPaint = null;
      window.webContents.invalidate();
      const paintDeadline = Date.now() + 2_000;
      while (!lastPaint && Date.now() < paintDeadline) {
        await new Promise((resolve) => setTimeout(resolve, 25));
      }
      if (!lastPaint) throw new Error(`Electron did not paint ${route.path} at ${viewport.width}x${viewport.height}`);
      fs.writeFileSync(path.join(outputRoot, file), lastPaint.toPNG());
      results.push({ requestedViewport: viewport, route, screenshot: file, ...audit });
    }
  }

  const failures = results.filter((result) => result.documentOverflowX
    || result.clippedControls.length
    || result.offscreenControls.length
    || result.pixelatedImages.length
    || result.reportRail?.horizontalOverflow
    || result.reportRail?.clippedMarks.length);
  const report = {
    generatedAt: new Date().toISOString(),
    baseUrl,
    apiUrl,
    checks: results,
    summary: { checks: results.length, passed: results.length - failures.length, failed: failures.length },
  };
  fs.writeFileSync(path.join(outputRoot, "visual-audit.json"), JSON.stringify(report, null, 2));
  console.log(`Electron visual audit: ${report.summary.passed}/${report.summary.checks} checks passed.`);
  if (failures.length) {
    for (const failure of failures) {
      console.error(`${failure.requestedViewport.name}/${failure.route.name}: overflow=${failure.documentOverflowX}, clipped=${failure.clippedControls.length}, offscreen=${failure.offscreenControls.length}, pixelated=${failure.pixelatedImages.length}, reportRailOverflow=${failure.reportRail?.horizontalOverflow ?? false}, reportRailClipped=${failure.reportRail?.clippedMarks.length ?? 0}`);
    }
  }
  window.destroy();
  app.exit(failures.length ? 1 : 0);
}

app.whenReady()
  .then(run)
  .catch((error) => {
    console.error(error);
    app.exit(1);
  });
