package ru.papajon.meshcentralmobile.security

import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.LifecycleRegistry
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Test

class AuthenticationForegroundTest {
    private class Owner : LifecycleOwner {
        val registry = LifecycleRegistry(this)
        override val lifecycle: Lifecycle get() = registry
    }
    @Test fun aStoppedOwnerExpiresAndCannotLaterReceiveSuccessfulAuthentication() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val owner = Owner()
        val result = CompletableDeferred<Boolean>()
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
        try {
            instrumentation.runOnMainSync {
                owner.registry.currentState = Lifecycle.State.STARTED
                scope.launch { result.complete(awaitAuthenticationForeground(owner.lifecycle, 50)) }
            }
            assertFalse(runBlocking { withTimeout(2000) { result.await() } })
            instrumentation.runOnMainSync { owner.registry.currentState = Lifecycle.State.RESUMED }
            assertFalse(runBlocking { result.await() })
        } finally { scope.cancel() }
    }
    @Test fun aResumedOwnerCanAcceptTheCurrentResult() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val owner = Owner()
        val result = CompletableDeferred<Boolean>()
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
        try {
            instrumentation.runOnMainSync {
                owner.registry.currentState = Lifecycle.State.STARTED
                scope.launch { result.complete(awaitAuthenticationForeground(owner.lifecycle, 1000)) }
                owner.registry.currentState = Lifecycle.State.RESUMED
            }
            assertTrue(runBlocking { withTimeout(2000) { result.await() } })
        } finally { scope.cancel() }
    }
}
