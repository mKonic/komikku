package eu.kanade.presentation.more.settings.screen.about

import io.kotest.matchers.types.shouldBeInstanceOf
import org.junit.jupiter.api.Test
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.ObjectInputStream
import java.io.ObjectOutputStream

class AboutScreenTest {

    /**
     * The screen stack is saved with Java serialization when Android stops the app in the background, and restored
     * from it when the app comes back. A readResolve that returned the companion restored About as a non-screen.
     */
    @Test
    fun `the About screen comes back as a screen after being saved`() {
        val saved = ByteArrayOutputStream().also { bytes ->
            ObjectOutputStream(bytes).use { it.writeObject(AboutScreen()) }
        }.toByteArray()

        val restored = ObjectInputStream(ByteArrayInputStream(saved)).use { it.readObject() }

        restored.shouldBeInstanceOf<AboutScreen>()
    }
}
