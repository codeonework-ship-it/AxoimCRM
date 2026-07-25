import {
  createContext,
  useCallback,
  useContext,
  useMemo,
  type ReactNode,
} from "react";
import { useMutation, useQuery, useQueryClient } from "@tanstack/react-query";
import { api, type NotificationItem } from "../api/client";

export interface AppNotification {
  id: string;
  kind: "action" | "signal" | "system";
  priority: "urgent" | "normal" | "low";
  title: string;
  body: string;
  time: string;
  reason: string;
  actionRequired: boolean;
  actionCompleted: boolean;
  read: boolean;
  href?: string;
}

interface NotificationsApi {
  notifications: AppNotification[];
  unreadCount: number;
  isLoading: boolean;
  isError: boolean;
  markRead: (id: string) => void;
  markUnread: (id: string) => void;
  markAllRead: () => void;
  refetch: () => void;
}

const NotificationsContext = createContext<NotificationsApi | null>(null);

function relativeTime(iso: string): string {
  const seconds = Math.max(0, Math.floor((Date.now() - new Date(iso).getTime()) / 1000));
  if (seconds < 60) return "Now";
  const minutes = Math.floor(seconds / 60);
  if (minutes < 60) return `${minutes}m ago`;
  const hours = Math.floor(minutes / 60);
  if (hours < 24) return `${hours}h ago`;
  const days = Math.floor(hours / 24);
  return days === 1 ? "Yesterday" : `${days}d ago`;
}

function toAppNotification(item: NotificationItem): AppNotification {
  return {
    id: item.id,
    kind: item.kind.toLowerCase() as AppNotification["kind"],
    priority: item.priority.toLowerCase() as AppNotification["priority"],
    title: item.title,
    body: item.body,
    time: relativeTime(item.occurredAt),
    reason: item.reason,
    actionRequired: item.actionRequired,
    actionCompleted: item.actionCompleted,
    read: item.read,
    href: item.href ?? undefined,
  };
}

export function NotificationsProvider({ children }: { children: ReactNode }) {
  const queryClient = useQueryClient();
  const feedQ = useQuery({
    queryKey: ["notifications", "feed"],
    queryFn: () => api.notifications("all"),
    retry: 1,
    refetchInterval: 60_000,
  });
  const countQ = useQuery({
    queryKey: ["notifications", "unread-count"],
    queryFn: api.notificationUnreadCount,
    retry: 1,
    refetchInterval: 60_000,
  });

  const refresh = useCallback(() => {
    void queryClient.invalidateQueries({ queryKey: ["notifications"] });
  }, [queryClient]);

  const readMutation = useMutation({
    mutationFn: ({ id, read }: { id: string; read: boolean }) =>
      read ? api.markNotificationRead(id) : api.markNotificationUnread(id),
    onSuccess: refresh,
  });
  const allReadMutation = useMutation({
    mutationFn: api.markAllNotificationsRead,
    onSuccess: refresh,
  });

  const notifications = useMemo(
    () => (feedQ.data ?? []).map(toAppNotification),
    [feedQ.data],
  );

  const value = useMemo<NotificationsApi>(() => ({
    notifications,
    unreadCount: countQ.data ?? notifications.filter((item) => !item.read).length,
    isLoading: feedQ.isLoading || countQ.isLoading,
    isError: feedQ.isError || countQ.isError,
    markRead: (id) => readMutation.mutate({ id, read: true }),
    markUnread: (id) => readMutation.mutate({ id, read: false }),
    markAllRead: () => allReadMutation.mutate(),
    refetch: refresh,
  }), [notifications, countQ.data, countQ.isLoading, countQ.isError,
    feedQ.isLoading, feedQ.isError, readMutation, allReadMutation, refresh]);

  return <NotificationsContext.Provider value={value}>{children}</NotificationsContext.Provider>;
}

export function useNotifications(): NotificationsApi {
  const context = useContext(NotificationsContext);
  if (!context) throw new Error("useNotifications must be used inside <NotificationsProvider>");
  return context;
}
