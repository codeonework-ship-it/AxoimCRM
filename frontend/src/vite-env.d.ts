/// <reference types="vite/client" />

interface Window {
  /** Injected by the Axiom desktop shell (electron-client) preload script. */
  axiomDesktop?: {
    notify: (title: string, body: string) => void;
    platform: string;
    versions: { electron: string; chrome: string; node: string };
  };
}
