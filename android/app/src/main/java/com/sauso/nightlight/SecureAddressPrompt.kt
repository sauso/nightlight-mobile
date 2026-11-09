package com.sauso.nightlight

import android.app.Activity
import android.app.AlertDialog
import android.os.Handler
import android.os.Looper
import java.util.concurrent.atomic.AtomicBoolean

/**
 * The native "Use this secure address?" prompt (Codex code review of PR #37; the owner chose "ask once in the app",
 * 2026-11-09). ServerConfigPlugin.addEndpoint shows it when EndpointRules.needsConfirmation and PromptGate say so.
 *
 * It is NOT cancelable (as on the iPhone, whose buttons carry no cancel style): the back key and a tap outside do nothing, so
 * neither can count as an explicit "Don't use it". [onShown] runs once it is on screen (the gate's cooldown starts then).
 *
 * It ends EXACTLY ONCE (the addendum review: exactly-once completion), with whichever comes first: a tap on "Use it" or
 * "Don't use it", a dismissal that is not a tap (the system closing it), the [PromptGate.ANSWER_TIMEOUT_MS] timeout,
 * [finish] from outside (ServerConfigPlugin.cancelPending, from MainActivity.onDestroy), no activity to show it in, or an
 * error while showing it. [onOutcome] is called once with that ending; PromptGate.effectOf says what it leads to (only the
 * explicit "Don't use it" tap stores a decline). Later endings are ignored: an AlertDialog button first calls its listener and
 * then dismisses the dialog (AlertController posts the dismiss after the click), so a tap is followed by a dismissal that must
 * change nothing; the once-guard makes that so whatever the order.
 *
 * The framework AlertDialog (android.app), not the AppCompat one: it needs no extra dependency and takes the activity's theme.
 * Glue: no JVM test reaches this; the phone checklist covers it (first learn, accept, decline, back button, timeout, a
 * rebuild while it shows).
 */
internal class SecureAddressPrompt(
    private val onShown: () -> Unit,
    private val onOutcome: (PromptGate.Outcome) -> Unit,
) {

    companion object {
        const val TITLE = "Use this secure address?"
        const val USE_IT = "Use it"
        const val DONT_USE_IT = "Don't use it"
    }

    private val done = AtomicBoolean(false)
    private val main = Handler(Looper.getMainLooper())
    private val timeout = Runnable { finish(PromptGate.Outcome.TIMED_OUT) }

    // The dialog on screen. Main thread only.
    private var dialog: AlertDialog? = null

    /** Shows the prompt on [activity], on the main thread. No activity, or one that is going away, ends it as NO_WINDOW. */
    fun show(activity: Activity?, message: String) {
        main.post {
            // Ended before it could be shown (cancelPending): nothing to show.
            if (done.get()) return@post
            if (activity == null || activity.isFinishing || activity.isDestroyed) {
                finish(PromptGate.Outcome.NO_WINDOW)
                return@post
            }
            try {
                val d = AlertDialog.Builder(activity)
                    .setTitle(TITLE)
                    .setMessage(message)
                    .setPositiveButton(USE_IT) { _, _ -> finish(PromptGate.Outcome.USE_IT) }
                    .setNegativeButton(DONT_USE_IT) { _, _ -> finish(PromptGate.Outcome.DONT_USE_IT) }
                    .setCancelable(false)
                    .setOnDismissListener { finish(PromptGate.Outcome.DISMISSED) }
                    .create()
                dialog = d
                d.show()
                main.postDelayed(timeout, PromptGate.ANSWER_TIMEOUT_MS)
                onShown()
            } catch (e: Exception) {
                // A window that cannot take the dialog (BadTokenException), say: refused, nothing stored.
                finish(PromptGate.Outcome.FAILED)
            }
        }
    }

    /** Ends the prompt with [outcome], the first time only. Callable from any thread. */
    fun finish(outcome: PromptGate.Outcome) {
        if (!done.compareAndSet(false, true)) return
        main.removeCallbacks(timeout)
        // Directly when already on the main thread: from MainActivity.onDestroy the window must be closed before it goes, or
        // Android reports a leaked window. Its dismiss listener then finds the prompt already ended.
        if (Looper.myLooper() == Looper.getMainLooper()) closeDialog() else main.post { closeDialog() }
        onOutcome(outcome)
    }

    private fun closeDialog() {
        val d = dialog ?: return
        dialog = null
        try {
            if (d.isShowing) d.dismiss()
        } catch (e: Exception) {
            // The window is already gone: nothing to close.
        }
    }
}
