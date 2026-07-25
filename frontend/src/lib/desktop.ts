/**
 * Bridge to the Axiom desktop shell (electron-client). When the app runs
 * inside Electron, the preload script exposes window.axiomDesktop; in a
 * plain browser this is a no-op.
 */
export function desktopNotify(title: string, body: string): void {
  try {
    window.axiomDesktop?.notify(title, body);
  } catch {
    /* never let desktop bridge failures break the web app */
  }
}

export function isDesktop(): boolean {
  return typeof window !== "undefined" && !!window.axiomDesktop;
}
