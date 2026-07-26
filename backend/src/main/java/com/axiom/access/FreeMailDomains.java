package com.axiom.access;

import java.util.Locale;
import java.util.Set;

/**
 * Consumer and disposable mail domains that a trial request is not accepted from.
 *
 * <p><b>Why this exists.</b> A trial provisions a whole isolated workspace with an
 * administrator account and 30 days of runtime. Tying that to a domain the
 * requester demonstrably controls at their employer is the cheapest available
 * signal that a real organisation is behind the request, and it is what makes
 * per-domain rate limiting mean anything at all — anyone can mint unlimited
 * gmail addresses, so a per-domain limit on gmail.com would be theatre.
 *
 * <p><b>What this is not.</b> It is not a spam filter and it is not claimed to be
 * exhaustive; a determined abuser can register a domain for a few pounds. It
 * raises the cost of casual abuse and nothing more. The list is held in code
 * rather than a table on purpose: it is a static policy that changes on the scale
 * of years, and a table would invite it being edited at 2am with no review.
 *
 * <p>The two categories are kept separate because the refusal wording differs —
 * "use your work address" is helpful advice, whereas a disposable-mailbox
 * provider is a deliberate evasion and is simply declined.
 */
public final class FreeMailDomains {

    /** Consumer mailbox providers. Real people, wrong address for this purpose. */
    private static final Set<String> CONSUMER = Set.of(
            "gmail.com", "googlemail.com", "yahoo.com", "yahoo.co.uk", "yahoo.co.in", "yahoo.fr",
            "yahoo.de", "ymail.com", "rocketmail.com", "hotmail.com", "hotmail.co.uk", "hotmail.fr",
            "outlook.com", "outlook.in", "live.com", "live.co.uk", "msn.com", "aol.com",
            "icloud.com", "me.com", "mac.com", "mail.com", "email.com", "gmx.com", "gmx.de",
            "gmx.net", "web.de", "t-online.de", "protonmail.com", "proton.me", "pm.me",
            "tutanota.com", "tuta.io", "zoho.com", "yandex.com", "yandex.ru", "mail.ru",
            "qq.com", "163.com", "126.com", "sina.com", "naver.com", "daum.net", "hanmail.net",
            "rediffmail.com", "sify.com", "indiatimes.com", "libero.it", "virgilio.it",
            "orange.fr", "wanadoo.fr", "free.fr", "laposte.net", "sfr.fr", "seznam.cz",
            "wp.pl", "o2.pl", "interia.pl", "onet.pl", "bol.com.br", "uol.com.br", "terra.com.br",
            "comcast.net", "sbcglobal.net", "verizon.net", "att.net", "bellsouth.net", "cox.net",
            "btinternet.com", "sky.com", "talktalk.net", "virginmedia.com", "bigpond.com",
            "optusnet.com.au", "shaw.ca", "rogers.com", "telus.net", "fastmail.com", "hushmail.com",
            "inbox.com", "zohomail.com", "hey.com");

    /** Throwaway-mailbox services. Declined outright rather than advised. */
    private static final Set<String> DISPOSABLE = Set.of(
            "mailinator.com", "guerrillamail.com", "sharklasers.com", "10minutemail.com",
            "10minutemail.net", "yopmail.com", "yopmail.fr", "temp-mail.org", "tempmail.com",
            "tempmailo.com", "throwawaymail.com", "throwaway.email", "trashmail.com",
            "trashmail.de", "getnada.com", "nada.email", "dispostable.com", "maildrop.cc",
            "mailnesia.com", "mytemp.email", "moakt.com", "emailondeck.com", "fakeinbox.com",
            "spamgourmet.com", "mailcatch.com", "burnermail.io", "anonaddy.com", "simplelogin.io",
            "33mail.com", "spam4.me", "grr.la", "byom.de", "discard.email", "mailsac.com");

    private FreeMailDomains() {}

    public static boolean isConsumer(String domain) {
        return domain != null && CONSUMER.contains(domain.toLowerCase(Locale.ROOT));
    }

    public static boolean isDisposable(String domain) {
        return domain != null && DISPOSABLE.contains(domain.toLowerCase(Locale.ROOT));
    }

    public static boolean isBlocked(String domain) {
        return isConsumer(domain) || isDisposable(domain);
    }

    /**
     * The refusal a human reads. It says what to do next, because a refusal that
     * does not is just a dead end for a prospect who was trying to buy something.
     */
    public static String refusal(String domain) {
        if (isDisposable(domain)) {
            return "That looks like a disposable mailbox provider (" + domain + "), which we do not "
                    + "provision trial workspaces for. Use your company email address and we will set "
                    + "up your workspace.";
        }
        return "Please use your work email address. We cannot provision a trial workspace against a "
                + "personal mailbox domain such as " + domain + ", because the workspace is created for "
                + "your organisation. If your company genuinely uses this domain for business email, "
                + "contact sales and a person will set it up for you.";
    }
}
