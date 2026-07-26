const SCREEN_INFO: Record<string, string> = {
  "User management": "Create users, see their role, and turn access on or off without deleting history.",
  "RBAC policies": "Shows what each role can read, change, export, or administer on every screen.",
  "Email and report alerts": "Set up messages and scheduled report emails before they are sent.",
  "Trial accounts": "Manage companies using Axiom for a limited demo period.",
  "Company setup": "Review tenant companies, trial dates, and account status.",
  "Company setup accounts": "Control company access, workspace status, and payment-related pauses.",
  Billing: "Review plan, invoices, payment state, and billing controls.",
  "Accounts results": "Organizations your team sells to or supports.",
  "Lead queue": "People or companies showing interest before they become accounts or deals.",
  "Activity timeline": "Calls, meetings, notes, tasks, and emails in one customer timeline.",
  "Product catalogue": "Products and services that can be priced, quoted, and sold.",
  "Price book register": "Price lists that decide which prices are active for products.",
  "Quote register": "Customer quotes and their approval or ordering status.",
  "Reference data": "Reusable dropdown values such as statuses, categories, and reasons.",
  Reports: "Saved business views that can be downloaded or sent as files.",
  "Global search": "Search records while respecting what your role is allowed to see.",
};

const FIELD_INFO: Record<string, string> = {
  Workspace: "The short workspace name for your company, for example meridian.",
  Email: "Use your work email address.",
  "Work email": "Use the company email that your workspace recognizes.",
  Password: "Enter the password given to you or created during activation.",
  "New password": "Choose the password you want to use from now on.",
  "Confirm password": "Type the same password again so we can catch mistakes.",
  "Display name": "The person's name as it should appear in Axiom.",
  Role: "The access level that decides what this user can see and do.",
  "Initial password": "Temporary password for the first sign-in.",
  Name: "A friendly name so people can recognize this setup later.",
  Subject: "The email subject line users will see.",
  To: "Main recipients who should receive the message.",
  CC: "People copied for awareness.",
  BCC: "Hidden recipients who receive a copy privately.",
  "Company name": "The legal or public name of the company.",
  "Full name": "Your first and last name.",
  "Job title": "Your work title or responsibility.",
  "Company size": "Approximate number of employees.",
  Country: "Where the company is mainly based.",
  Notes: "Any extra context that helps the team understand the request.",
  Search: "Type a few words to narrow the list.",
  "Industry filter": "Show only accounts from one industry.",
  "Status filter": "Show only records in one status.",
  Status: "Choose which status you want to see.",
  Type: "Choose the kind of record you want to see.",
  Category: "Filter records by their category.",
  "Related record UUID": "The internal ID of the account, lead, contact, or opportunity this activity belongs to.",
  "Due date": "When the work should be completed.",
  "Reminder date": "When Axiom should remind the owner.",
  "Mail body": "The message content users will read in the email.",
  Formats: "Choose the file types to attach, such as PDF, Excel, or Word.",
};

export function screenInfo(title: string): string {
  return SCREEN_INFO[title] ?? "This screen shows the records and actions for this part of Axiom.";
}

export function fieldInfo(label: string, fallback?: string): string {
  return FIELD_INFO[label] ?? fallback ?? "This field helps Axiom save the right information.";
}

export function InfoTag({ text, label = "Information" }: { text: string; label?: string }) {
  return (
    <span
      className="info-tag"
      tabIndex={0}
      role="note"
      aria-label={`${label}: ${text}`}
      data-info={text}
    >
      i
    </span>
  );
}

export function InfoLabel({
  htmlFor,
  children,
  info,
  className,
}: {
  htmlFor?: string;
  children: string;
  info?: string;
  className?: string;
}) {
  return (
    <label className={className} htmlFor={htmlFor}>
      <span>{children}</span>
      <InfoTag text={info ?? fieldInfo(children)} label={`${children} help`} />
    </label>
  );
}
