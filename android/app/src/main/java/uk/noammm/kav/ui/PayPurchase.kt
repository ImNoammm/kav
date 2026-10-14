package uk.noammm.kav.ui

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.sync.Mutex
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import uk.noammm.kav.data.RavPass

// Recover a payment whose reply was lost by checking whether a new ticket appeared on the account.
internal class PayPurchase {
    private val lock = Mutex()
    var busy by mutableStateOf(false)
        private set

    class Unconfirmed(cause: Exception) : Exception(cause)

    // snapshot: the account's latest ticket. A ticket newer than the one before, bought after the payment started, is
    // the payment's own.
    suspend fun run(
        snapshot: suspend () -> RavPass.Ticket?,
        buy: suspend () -> RavPass.Ticket,
        prepare: suspend () -> Unit = {},
        now: () -> Long = System::currentTimeMillis,
    ): RavPass.Ticket {
        if (!lock.tryLock()) throw RavPass.Refused("", T("A payment is already in progress.", "כבר מתבצע תשלום."))
        busy = true
        try {
            // Without the ticket before, a lost reply cannot be recovered safely, so the payment is not sent.
            val before = snapshot()?.id
            val started = now()
            prepare()
            return try { buy() } catch (e: CancellationException) { throw e } catch (e: Exception) {
                if (e is RavPass.Refused || e is RavPass.Unauthorized) throw e
                val after = try { snapshot() } catch (cancel: CancellationException) { throw cancel }
                    catch (failed: Exception) { throw Unconfirmed(e) }
                // HopOn's clock and the phone's can differ by a little.
                after?.takeIf { it.id != before && it.boughtUtc >= started - 120_000 } ?: throw Unconfirmed(e)
            }
        } finally {
            busy = false
            lock.unlock()
        }
    }
}
