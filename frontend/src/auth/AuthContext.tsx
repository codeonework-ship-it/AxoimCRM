import {
  createContext,
  useCallback,
  useContext,
  useEffect,
  useMemo,
  useState,
  type ReactNode,
} from "react";
import {
  api,
  setAuthToken,
  setUnauthorizedHandler,
  type AuthTenant,
  type AuthUser,
  type LoginRequest,
} from "../api/client";

const STORAGE_KEY = "axiom.session";

interface StoredSession {
  token: string;
  user: AuthUser;
  tenant: AuthTenant;
}

interface AuthState {
  user: AuthUser | null;
  tenant: AuthTenant | null;
  isAuthenticated: boolean;
  login: (req: LoginRequest) => Promise<void>;
  logout: () => void;
}

const AuthContext = createContext<AuthState | null>(null);

function loadSession(): StoredSession | null {
  try {
    const raw = localStorage.getItem(STORAGE_KEY);
    if (!raw) return null;
    const parsed = JSON.parse(raw) as StoredSession;
    if (!parsed?.token || !parsed?.user || !parsed?.tenant) return null;
    return parsed;
  } catch {
    return null;
  }
}

export function AuthProvider({ children }: { children: ReactNode }) {
  const [session, setSession] = useState<StoredSession | null>(() => {
    const stored = loadSession();
    // Prime the in-memory token before the first render so early queries
    // carry Authorization.
    setAuthToken(stored?.token ?? null);
    return stored;
  });

  const logout = useCallback(() => {
    setAuthToken(null);
    localStorage.removeItem(STORAGE_KEY);
    setSession(null);
  }, []);

  const login = useCallback(async (req: LoginRequest) => {
    const res = await api.login(req);
    const next: StoredSession = {
      token: res.token,
      user: res.user,
      tenant: res.tenant,
    };
    setAuthToken(res.token);
    localStorage.setItem(STORAGE_KEY, JSON.stringify(next));
    setSession(next);
  }, []);

  // Any 401 from the API drops the session; the route guard then
  // redirects to /login.
  useEffect(() => {
    setUnauthorizedHandler(logout);
    return () => setUnauthorizedHandler(null);
  }, [logout]);

  const value = useMemo<AuthState>(
    () => ({
      user: session?.user ?? null,
      tenant: session?.tenant ?? null,
      isAuthenticated: !!session,
      login,
      logout,
    }),
    [session, login, logout],
  );

  return <AuthContext.Provider value={value}>{children}</AuthContext.Provider>;
}

export function useAuth(): AuthState {
  const ctx = useContext(AuthContext);
  if (!ctx) throw new Error("useAuth must be used inside <AuthProvider>");
  return ctx;
}
