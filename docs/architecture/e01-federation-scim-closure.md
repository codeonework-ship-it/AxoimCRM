# E01 Federation and SCIM Closure

## Outcome

The first-party E01 engine is complete for live SAML 2.0, OIDC authorization-code/PKCE and SCIM 2.0 User/Group lifecycle operations. Production certification is deliberately evidence-based: Axiom does not award a provider pass from a local mock or configuration probe. A pass requires the external provider tenant reference, connector job reference and every required lifecycle control.

## Trust boundaries

- Public discovery answers only one workspace/email pair and does not reveal whether a user exists.
- OIDC uses authorization code, S256 PKCE, nonce, single-use state, exact issuer/audience/`azp` checks, RSA JWKS signature verification and bounded clock skew.
- SAML requires one directly nested, signed assertion whose signature references that assertion ID. Issuer, audience, destination, `InResponseTo`, bearer confirmation and validity windows are checked. DTDs and external entities are disabled.
- Login is bound to the initiating browser by a ten-minute HttpOnly state cookie. SAML POST uses `SameSite=None; Secure` behind HTTPS; OIDC uses `SameSite=Lax`.
- Provider URLs require HTTPS, never follow redirects and cannot resolve to local/private addresses. The localhost exception is an explicit test-only property.
- Verified subjects are linked by `(tenant, provider, subject)`. JIT is disabled by default and may create only tenant human-user roles.
- The callback returns a two-minute, one-use ticket, not an access token. Ticket exchange issues the normal tenant JWT and server-side session.
- Provider secrets remain encrypted and write-only. Local administrator sign-in remains available for recovery.

## SCIM contract

`/scim/v2` supplies ServiceProviderConfig, Schemas and ResourceTypes plus paginated/filterable Users and Groups. A tenant-bound bearer token carries separate `users:read`, `users:write`, `groups:read` and `groups:write` scopes.

User delete is deactivation: sessions are revoked and owned CRM records remain attributed. Group delete deactivates the governed `security.user_group` master and removes live memberships without deleting audit history. External IDs and weak resource versions are retained in identity link tables.

## Production certification gate

A certification row is append-only and passes only when all controls below are affirmed with an external tenant and connector/test-job reference:

1. Federated login and issuer/audience/nonce validation.
2. SCIM discovery.
3. User create, update and deactivate.
4. Session revocation and owned-record preservation.
5. Group create, membership and deactivate.
6. Filter and pagination behavior.

The **Access governance** screen exposes the live prerequisite test and evidence register. A failed or incomplete run stays failed and lists its missing evidence.

## Operations runbook

1. Configure the provider while disabled and run **Test configuration**.
2. Run **Test live federation** to retrieve OIDC discovery/JWKS or validate SAML endpoint/certificate prerequisites.
3. Configure the provider callback/ACS and, for SAML, import Axiom service-provider metadata.
4. Enable the provider and complete a real browser sign-in.
5. Issue a short-lived SCIM token with only the connector's required scopes.
6. Run the provider's provisioning job and verify the complete User/Group lifecycle.
7. Record the provider tenant, job ID and evidence in **Production certification**.
8. Revoke the test token. Rotate production tokens and SAML certificates through the governed screens.

## External release gate

The codebase and local protocol lifecycle are verified. Named Microsoft Entra ID/Okta production certification remains an external release gate until credentials to a real provider tenant and its connector-job evidence are supplied. This is external authority, not unfinished first-party code.
