import { Component, type ErrorInfo, type ReactNode } from "react";
import { clearGridPreferences } from "../lib/usePersistedGridState";

interface Props {
  children: ReactNode;
}

interface State {
  error: Error | null;
}

export class ErrorBoundary extends Component<Props, State> {
  state: State = { error: null };

  static getDerivedStateFromError(error: Error): State {
    return { error };
  }

  componentDidCatch(error: Error, info: ErrorInfo): void {
    console.error("Axiom UI crashed:", error, info.componentStack);
  }

  private retry = () => {
    this.setState({ error: null });
  };

  private resetViewsAndReload = () => {
    clearGridPreferences();
    window.location.reload();
  };

  render() {
    if (this.state.error) {
      const message = this.state.error.message;
      const isModuleLoadError = /dynamically imported module|loading chunk|chunkloaderror|imported module/i
        .test(message);
      return (
        <div className="fullpage-state app-ground">
          <div className="fullpage-inner">
            <span className="label">{isModuleLoadError ? "Module link down" : "Fault isolated"}</span>
            <h1>{isModuleLoadError ? "Screen failed to load" : "Something broke"}</h1>
            <p>
              {isModuleLoadError
                ? "This screen did not load cleanly, usually because the browser has an older cached module. Retry first; reload if the same message returns."
                : "The interface hit an unexpected error and stopped to avoid corrupting your view. Retry the screen or reload the app if it repeats."}
            </p>
            <p>
              <code>{message}</code>
            </p>
            <div className="fullpage-actions">
              <button className="btn" onClick={this.retry}>Retry screen</button>
              <button className="btn" onClick={this.resetViewsAndReload}>Reset views</button>
              <button className="btn btn-primary" onClick={() => window.location.reload()}>
                Reload app
              </button>
            </div>
          </div>
        </div>
      );
    }
    return this.props.children;
  }
}
