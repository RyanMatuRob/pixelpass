package com.pixelpass.ui.session

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.pixelpass.PixelPassApp
import com.pixelpass.core.PixelPassLog
import com.pixelpass.data.session.SessionFactory
import com.pixelpass.data.session.SessionPolicy
import com.pixelpass.data.session.UserSession
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

sealed interface SessionState {
    data object Loading : SessionState
    data object SignedOut : SessionState
    data class SignedIn(val session: UserSession) : SessionState
}

/**
 * The single source of truth for "who is signed in and until when".
 *
 * Startup is deliberately strict: a stored token is only reused while its local
 * TTL holds; otherwise the secure storage is wiped before the UI is shown.
 *
 * `@JvmOverloads` is required, not cosmetic: the injectable `clock` default stops
 * Kotlin from emitting a `SessionViewModel(Application)` constructor, which is the
 * exact signature `AndroidViewModelFactory` looks up by reflection. Without it the
 * session gate dies on launch with `NoSuchMethodException`.
 */
class SessionViewModel @JvmOverloads constructor(
    application: Application,
    private val clock: () -> Long = System::currentTimeMillis
) : AndroidViewModel(application) {

    private val container = (application as PixelPassApp).container
    private val sessionStore = container.sessionStore

    private val _state = MutableStateFlow<SessionState>(SessionState.Loading)
    val state: StateFlow<SessionState> = _state.asStateFlow()

    init {
        restore()
        watchExpiry()
    }

    fun signIn(token: String, role: String, keepSignedIn: Boolean) {
        val session = SessionFactory.fromLogin(token, role, keepSignedIn, clock())
        if (session == null) {
            PixelPassLog.e(PixelPassLog.SESSION, "Login response did not contain a readable JWT")
            _state.value = SessionState.SignedOut
            return
        }

        _state.value = SessionState.SignedIn(session)
        PixelPassLog.i(
            PixelPassLog.SESSION,
            "Signed in as ${session.userId} (${session.role}); TTL ${session.remainingMillis(clock()) / 60_000} min, persisted=${session.keepSignedIn}"
        )

        viewModelScope.launch {
            if (session.keepSignedIn) {
                sessionStore.save(session)
            } else {
                sessionStore.clear()
            }
        }
    }

    fun signOut() {
        signOut(SessionState.SignedOut, "Signed out")
    }

    private fun restore() {
        viewModelScope.launch {
            val stored = sessionStore.load()
            when {
                stored == null -> {
                    PixelPassLog.d(PixelPassLog.SESSION, "No stored session; showing sign-in")
                    _state.value = SessionState.SignedOut
                }

                SessionPolicy.isExpired(stored.expiresAtEpochMillis, clock()) -> {
                    PixelPassLog.i(PixelPassLog.SESSION, "Stored session expired at ${stored.expiresAtEpochMillis}; clearing")
                    sessionStore.clear()
                    _state.value = SessionState.SignedOut
                }

                else -> {
                    PixelPassLog.i(
                        PixelPassLog.SESSION,
                        "Restored session for ${stored.userId} with ${stored.remainingMillis(clock()) / 60_000} min left"
                    )
                    _state.value = SessionState.SignedIn(stored)
                }
            }
        }
    }

    /** A running app must not outlive the TTL either, so it is re-checked on a timer. */
    private fun watchExpiry() {
        viewModelScope.launch {
            while (isActive) {
                delay(EXPIRY_CHECK_INTERVAL_MILLIS)
                val current = _state.value as? SessionState.SignedIn ?: continue
                if (SessionPolicy.isExpired(current.session.expiresAtEpochMillis, clock())) {
                    signOut(SessionState.SignedOut, "Session TTL elapsed; signing out")
                }
            }
        }
    }

    private fun signOut(next: SessionState, reason: String) {
        PixelPassLog.i(PixelPassLog.SESSION, reason)
        _state.value = next
        viewModelScope.launch { sessionStore.clear() }
    }

    private companion object {
        const val EXPIRY_CHECK_INTERVAL_MILLIS = 15_000L
    }
}
