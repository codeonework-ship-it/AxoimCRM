import { Component, type ErrorInfo, type ReactNode } from "react";

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

  render() {
    if (this.state.error) {
      return (
        <div className="fullpage-state app-ground">
          <div className="fullpage-inner">
            <span className="label">Fault isolated</span>
            <h1>Something broke</h1>
            <p>
              The interface hit an unexpected error and stopped to avoid
              corrupting your view. Reloading usually clears it.
            </p>
            <p>
              <code>{this.state.error.message}</code>
            </p>
            <button
              className="btn btn-primary"
              onClick={() => window.location.reload()}
            >
              Reload
            </button>
          </div>
        </div>
      );
    }
    return this.props.children;
  }
}
