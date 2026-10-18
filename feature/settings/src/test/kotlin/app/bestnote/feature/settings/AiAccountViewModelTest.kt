package app.bestnote.feature.settings

import app.bestnote.ai.vision.providers.account.AccountError
import app.bestnote.ai.vision.providers.account.AccountStatus
import app.bestnote.ai.vision.providers.account.SignInFailure
import app.bestnote.ai.vision.providers.account.SignInPhase
import app.bestnote.feature.settings.data.AiAccountGateway
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class AiAccountViewModelTest {
    private class FakeGateway : AiAccountGateway {
        override val phase = MutableStateFlow<SignInPhase>(SignInPhase.Idle)
        var signedIn = false
        var active = false
        var status: () -> AccountStatus = { AccountStatus(usage = 1.5, usageMonthly = 0.25) }
        override fun signedIn() = signedIn
        override fun keyHint() = if (signedIn) "…abcd" else null
        override suspend fun start(): String {
            phase.value = SignInPhase.Waiting("https://openrouter.ai/auth?x")
            return "https://openrouter.ai/auth?x"
        }
        override fun cancel() {
            phase.value = SignInPhase.Failed(SignInFailure.Cancelled)
        }
        override fun acknowledge() {
            phase.value = SignInPhase.Idle
        }
        override suspend fun signOut() {
            signedIn = false
            active = false
        }
        override suspend fun status() = status.invoke()
        override suspend fun manageUrl() = if (signedIn) "https://openrouter.ai/keys/hash" else null
        override fun inUse() = active
        override suspend fun use() {
            active = true
        }

        /** What AiAccounts does when the browser comes back with a good code. */
        fun completeSignIn() {
            signedIn = true
            active = true
            phase.value = SignInPhase.Done
        }
    }

    private val gateway = FakeGateway()

    @Before fun setUp() = Dispatchers.setMain(UnconfinedTestDispatcher())

    @After fun tearDown() = Dispatchers.resetMain()

    @Test fun signInOpensTheBrowserThenShowsTheAccount() = runTest {
        val vm = AiAccountViewModel(gateway)
        assertFalse(vm.state.value.signedIn)
        vm.signIn()
        assertEquals("https://openrouter.ai/auth?x", vm.open.first())
        assertTrue(vm.state.value.busy)

        gateway.completeSignIn()
        val s = vm.state.value
        assertTrue(s.signedIn)
        assertTrue(s.inUse)
        assertEquals("…abcd", s.keyHint)
        assertEquals(AccountStatusUi.Ready(AccountStatus(usage = 1.5, usageMonthly = 0.25)), s.status)
        assertEquals(1, vm.changed.value) // the provider list below reloads
    }

    @Test fun cancelAndFailuresAreShown() {
        val vm = AiAccountViewModel(gateway)
        vm.signIn()
        vm.cancel()
        assertEquals(SignInFailure.Cancelled, vm.state.value.failure)
        assertFalse(vm.state.value.busy)
        vm.dismissResult()
        assertNull(vm.state.value.failure)
    }

    @Test fun revokedKeyAsksToSignInAgain() {
        gateway.signedIn = true
        gateway.status = { throw AccountError.KeyRevoked() }
        val vm = AiAccountViewModel(gateway)
        assertEquals(AccountStatusUi.Revoked, vm.state.value.status)
        gateway.status = { throw AccountError.BadResponse("x") }
        vm.loadStatus()
        assertEquals(AccountStatusUi.Unavailable, vm.state.value.status)
    }

    @Test fun useAndSignOut() = runTest {
        gateway.signedIn = true
        val vm = AiAccountViewModel(gateway)
        assertFalse(vm.state.value.inUse)
        vm.use()
        assertTrue(vm.state.value.inUse)
        vm.manage()
        assertEquals("https://openrouter.ai/keys/hash", vm.open.first())
        vm.signOut()
        assertFalse(vm.state.value.signedIn)
        assertNull(vm.state.value.status)
        assertEquals(2, vm.changed.value)
    }
}
