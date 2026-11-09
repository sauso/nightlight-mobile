package com.sauso.nightlight

/**
 * The rules around the native "Use this secure address?" prompt (Codex code review of PR #37, a High finding twice; the owner
 * chose "ask once in the app", 2026-11-09; the plan's addendum and its review). EndpointRules.needsConfirmation says WHETHER an
 * offer needs the person's yes; this says whether to show the prompt NOW, and what each way it can end leads to.
 *
 * Pure Kotlin with no Android imports, so the JVM unit tests (PromptGateTest) run exactly this code. The dialog itself, its
 * timeout and cancelPending are glue (SecureAddressPrompt, ServerConfigPlugin), which only a phone can test.
 *
 * One instance lives for the whole process (ServerConfigPlugin's companion): the one-at-a-time rule and the cooldown are per
 * process, so a recreated activity cannot reset them. Thread-safe: [reserve] and [release] are synchronized, so two offers
 * arriving together cannot both be shown (the addendum review: "the gate reserves atomically").
 */
class PromptGate {

    companion object {
        // How long a "Don't use it" tap keeps that same address from being asked about again, by the WALL clock (it is stored and
        // must survive restarts). A judgement, not a measurement (the addendum review, 2026-11-09): the web app offers the address
        // at every start, so without a memory a declined address would be asked about every time (a nag); without an expiry a
        // hostile page that got the REAL address declined first could silence it for good. A week bounds that, and then the
        // person is asked once more.
        const val DECLINE_LIFETIME_MS = 7L * 24 * 60 * 60 * 1000

        // At most one prompt per this long, per process, counted from when a prompt was shown. A judgement, not a measurement:
        // a hostile page that offers a new address every second cannot storm the person with questions; an offer refused by it
        // is only "not saved", and the next start offers it again.
        const val COOLDOWN_MS = 10L * 60 * 1000

        // How long an unanswered prompt waits before it ends as "not answered" (no decline stored). A judgement, not a measurement:
        // long enough to read the address and compare it with the server's Settings, short enough that the web app, which waits
        // for the answer, is not left waiting for ever (the addendum review: exactly-once completion).
        const val ANSWER_TIMEOUT_MS = 120_000L

        /**
         * Whether to show the prompt now for [candidate]. In this order:
         *  1. a prompt is already [showing] -> BUSY (one at a time; the call is refused, nothing is stored);
         *  2. the person declined this same address less than [DECLINE_LIFETIME_MS] ago -> REJECT_QUIETLY (not asked again);
         *  3. a prompt was SHOWN less than [COOLDOWN_MS] ago ([lastPromptAtMs], by the process clock [nowMs], a monotonic one) ->
         *     BUSY (refused, nothing stored: once the cooldown has passed the next offer is asked about);
         *  4. otherwise ASK.
         * [declinedCandidate] and [declinedAtWallMs] are the stored decline (null when none); [nowWallMs] is the wall clock now.
         */
        fun decide(
            candidate: String,
            declinedCandidate: String?,
            declinedAtWallMs: Long?,
            nowWallMs: Long,
            lastPromptAtMs: Long?,
            nowMs: Long,
            showing: Boolean,
        ): Decision {
            if (showing) return Decision.BUSY
            if (isDeclined(candidate, declinedCandidate, declinedAtWallMs, nowWallMs)) return Decision.REJECT_QUIETLY
            if (lastPromptAtMs != null) {
                val since = nowMs - lastPromptAtMs
                if (since >= 0L && since < COOLDOWN_MS) return Decision.BUSY
            }
            return Decision.ASK
        }

        /**
         * Whether a stored decline still applies to [candidate]: the same address, declined less than [DECLINE_LIFETIME_MS]
         * ago. At exactly the lifetime it has expired. A decline dated in the FUTURE (the phone's clock was set back) does not
         * apply: trusting it could stretch "a week at most" without bound, and asking once more costs one question.
         */
        fun isDeclined(candidate: String, declinedCandidate: String?, declinedAtWallMs: Long?, nowWallMs: Long): Boolean {
            if (declinedCandidate == null || declinedAtWallMs == null || declinedCandidate != candidate) return false
            val age = nowWallMs - declinedAtWallMs
            return age >= 0L && age < DECLINE_LIFETIME_MS
        }

        /**
         * What a prompt that ended in [outcome] leads to. Only the person's two taps are an answer: "Use it" saves, "Don't use
         * it" stores the decline and refuses. EVERY other ending (closed without a tap, the timeout, a lifecycle cancel, no window
         * to show it in, an error) refuses and stores NOTHING (the addendum review: a decline the person did not give must never
         * silence the address for a week). The back key and a tap outside cannot close it (SecureAddressPrompt: not cancelable),
         * so neither can be mistaken for a "Don't use it".
         */
        fun effectOf(outcome: Outcome): Effect = when (outcome) {
            Outcome.USE_IT -> Effect.SAVE
            Outcome.DONT_USE_IT -> Effect.REMEMBER_DECLINE_AND_REFUSE
            Outcome.DISMISSED, Outcome.TIMED_OUT, Outcome.CANCELLED, Outcome.NO_WINDOW, Outcome.FAILED -> Effect.REFUSE
        }

        /**
         * The refusal for a call the gate did not ask about: a decline still in force is NOT_ACCEPTED (the person said no), a busy
         * gate NOT_CONFIRMED (nothing was declined, it was just not asked now). The same mapping as the iPhone app, whose
         * sentences these are (EndpointRules.refusal). ASK is not a refusal (OK).
         */
        fun refusalWhenNotAsked(decision: Decision): EndpointRules.AddCheck = when (decision) {
            Decision.REJECT_QUIETLY -> EndpointRules.AddCheck.NOT_ACCEPTED
            Decision.BUSY -> EndpointRules.AddCheck.NOT_CONFIRMED
            Decision.ASK -> EndpointRules.AddCheck.OK
        }

        /** The refusal after a prompt that ended without "Use it": the explicit decline is NOT_ACCEPTED, anything else NOT_CONFIRMED. */
        fun refusalAfter(effect: Effect): EndpointRules.AddCheck = when (effect) {
            Effect.REMEMBER_DECLINE_AND_REFUSE -> EndpointRules.AddCheck.NOT_ACCEPTED
            Effect.REFUSE -> EndpointRules.AddCheck.NOT_CONFIRMED
            Effect.SAVE -> EndpointRules.AddCheck.OK
        }

        /**
         * The prompt's text: the saved server's own address the offer came from ([savedAddress]), the FULL secure host (never
         * shortened: the name is the one thing the person can compare), and when to accept. [candidate] has the one accepted
         * shape `https://<name>.ts.net` (EndpointRules.isTailscaleUrl), so the host is everything after the scheme.
         */
        fun promptBody(savedAddress: String, candidate: String): String =
            "Your server at $savedAddress offered this secure address:\n\n" +
                "${candidate.removePrefix("https://")}\n\n" +
                "Only use it if you have just turned on the secure address in your server's Settings → Remote Access " +
                "and the name matches the one shown there."
    }

    /** What [decide] says: show the prompt, refuse without asking (declined recently), or refuse because one is showing or was just shown. */
    enum class Decision { ASK, REJECT_QUIETLY, BUSY }

    /** How a prompt ended. Only USE_IT and DONT_USE_IT are the person's answer. */
    enum class Outcome { USE_IT, DONT_USE_IT, DISMISSED, TIMED_OUT, CANCELLED, NO_WINDOW, FAILED }

    /** What the plugin does after a prompt ended (see [effectOf]). */
    enum class Effect { SAVE, REMEMBER_DECLINE_AND_REFUSE, REFUSE }

    private var showing = false
    private var lastPromptAtMs: Long? = null

    /**
     * [decide], and when it says ASK, take the one prompt slot in the same step, so two offers arriving together cannot both be
     * shown. BUSY and REJECT_QUIETLY change nothing. Every ASK must be followed by exactly one [release].
     */
    @Synchronized
    fun reserve(candidate: String, declinedCandidate: String?, declinedAtWallMs: Long?, nowWallMs: Long, nowMs: Long): Decision {
        val decision = decide(candidate, declinedCandidate, declinedAtWallMs, nowWallMs, lastPromptAtMs, nowMs, showing)
        if (decision == Decision.ASK) showing = true
        return decision
    }

    /**
     * The reserved prompt is now on screen, at [nowMs] (the process clock): the cooldown starts HERE, not at [reserve] (as on the
     * iPhone), so a prompt that could not be shown (no activity, an error) does not hold back the next start's question.
     */
    @Synchronized
    fun shown(nowMs: Long) {
        lastPromptAtMs = nowMs
    }

    /** The prompt that [reserve] allowed has ended (any outcome). The cooldown, if it was shown, keeps running from then. */
    @Synchronized
    fun release() {
        showing = false
    }
}
