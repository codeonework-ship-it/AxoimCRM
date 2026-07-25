import { useMutation, useQuery, useQueryClient } from "@tanstack/react-query";
import { api, isUnreachable, type Lead } from "../api/client";
import { ApiUnreachable } from "../components/ApiUnreachable";
import { useToasts } from "../components/Toasts";

const CONVERTIBLE = new Set(["NEW", "QUALIFIED"]);

export function LeadsPage() {
  const queryClient = useQueryClient();
  const toasts = useToasts();

  const leadsQ = useQuery({
    queryKey: ["leads"],
    queryFn: api.leads,
    retry: 1,
  });

  const convertMutation = useMutation({
    mutationFn: (leadId: string) => api.convertLead(leadId),
  });

  function convert(lead: Lead) {
    convertMutation.mutate(lead.id, {
      onSuccess: () => {
        toasts.push(
          "info",
          "Lead converted",
          `${lead.name} is now an account, contact and opportunity.`,
        );
        void queryClient.invalidateQueries({ queryKey: ["leads"] });
        void queryClient.invalidateQueries({ queryKey: ["accounts"] });
        void queryClient.invalidateQueries({ queryKey: ["pipeline", "board"] });
        void queryClient.invalidateQueries({ queryKey: ["dashboard", "summary"] });
        void queryClient.invalidateQueries({ queryKey: ["notifications"] });
      },
      onError: (err) => {
        toasts.push(
          "error",
          "Convert failed",
          isUnreachable(err)
            ? "API unreachable — lead not converted."
            : err instanceof Error
              ? err.message
              : "Unknown error.",
        );
      },
    });
  }

  if (isUnreachable(leadsQ.error)) {
    return (
      <ApiUnreachable
        onRetry={() => void leadsQ.refetch()}
        retrying={leadsQ.isFetching}
      />
    );
  }

  return (
    <>
      <div className="page-head">
        <div><span className="eyebrow">Demand operations</span><h1>Leads</h1><p>Qualify intent and convert cleanly into revenue records.</p></div>
        {leadsQ.isSuccess && (
          <span className="count">{leadsQ.data.length} total</span>
        )}
      </div>

      {leadsQ.isLoading && <p className="loading-note">Loading leads…</p>}

      {leadsQ.isError && (
        <p className="empty-note">
          Leads failed to load
          {leadsQ.error instanceof Error ? `: ${leadsQ.error.message}` : "."}
        </p>
      )}

      {leadsQ.isSuccess && leadsQ.data.length === 0 && (
        <p className="empty-note">No leads yet.</p>
      )}

      {leadsQ.isSuccess &&
        leadsQ.data.map((lead) => (
          <div className="lead-row" key={lead.id}>
            <div>
              <div className="lead-name">{lead.name}</div>
              <div className="lead-meta">
                {[lead.company, lead.email, lead.ownerName]
                  .filter(Boolean)
                  .join(" · ") || "—"}
              </div>
            </div>
            <span
              className={`chip chip-${lead.status.toLowerCase()}`}
              style={{ marginLeft: "auto" }}
            >
              {lead.status}
            </span>
            {CONVERTIBLE.has(lead.status) && (
              <button
                className="btn btn-sm"
                onClick={() => convert(lead)}
                disabled={
                  convertMutation.isPending &&
                  convertMutation.variables === lead.id
                }
              >
                {convertMutation.isPending &&
                convertMutation.variables === lead.id
                  ? "Converting…"
                  : "Convert"}
              </button>
            )}
          </div>
        ))}
    </>
  );
}
