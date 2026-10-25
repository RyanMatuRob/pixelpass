package com.pixelpass.ui.session

import android.app.Application
import org.junit.Assert.assertNotNull
import org.junit.Test

/**
 * Guards the reflective contract `AndroidViewModelFactory` depends on: an
 * `AndroidViewModel` must expose a public `(Application)` constructor. A Kotlin
 * default parameter silently removes that overload, which made the session gate
 * crash on launch with `NoSuchMethodException` (issue #97).
 */
class SessionViewModelFactoryTest {

    @Test
    fun exposesApplicationConstructorForAndroidViewModelFactory() {
        assertNotNull(SessionViewModel::class.java.getConstructor(Application::class.java))
    }
}
