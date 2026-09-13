package eu.kanade.tachiyomi.extension.installer

import android.app.Service
import eu.kanade.tachiyomi.extension.ExtensionManager
import eu.kanade.tachiyomi.extension.model.InstallStep
import io.mockk.clearMocks
import io.mockk.mockk
import io.mockk.verify
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.addSingleton

class InstallerTest {

    @BeforeEach
    fun setUp() {
        clearMocks(extensionManager, service)
    }

    @Test
    fun `cancelling a queued install drops it before it starts`() {
        val installer = TestInstaller()
        installer.addToQueue(1, mockk())
        installer.addToQueue(2, mockk())

        Installer.cancelInstallQueue(2)
        installer.continueQueue(InstallStep.Installed)

        verify { extensionManager.updateInstallStep(2, InstallStep.Idle) }
        verify(exactly = 0) { extensionManager.setInstalling(2) }
        verify { service.stopSelf() }
        installer.onDestroy()
    }

    @Test
    fun `a dying service fails every install it still held and stops taking cancels`() {
        val installer = TestInstaller()
        installer.addToQueue(1, mockk())
        installer.addToQueue(2, mockk())

        installer.onDestroy()
        Installer.cancelInstallQueue(1)

        // Without this the install mid-flight when the service died sat at "Installing" forever.
        verify { extensionManager.updateInstallStep(1, InstallStep.Error) }
        verify { extensionManager.updateInstallStep(2, InstallStep.Error) }
        verify(exactly = 0) { extensionManager.updateInstallStep(1, InstallStep.Idle) }
    }

    private class TestInstaller : Installer(service) {
        override var ready = true
    }

    private companion object {
        // One of each for the whole class: Injekt keeps the first instance registered, and the
        // installer reaches its ExtensionManager through Injekt.
        val extensionManager = mockk<ExtensionManager>(relaxed = true)
        val service = mockk<Service>(relaxed = true)

        init {
            Injekt.addSingleton(extensionManager)
        }
    }
}
