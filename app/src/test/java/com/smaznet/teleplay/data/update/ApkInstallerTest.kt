package com.smaznet.teleplay.data.update

import org.junit.Assert.assertEquals
import org.junit.Test

class ApkInstallerTest {
    @Test fun belowApi26_alwaysInstalls() {
        assertEquals(InstallDecision.Install, installDecision(25, canRequestInstalls = false))
        assertEquals(InstallDecision.Install, installDecision(21, canRequestInstalls = false))
    }

    @Test fun api26Plus_needsPermissionUnlessAllowed() {
        assertEquals(InstallDecision.NeedPermission, installDecision(26, canRequestInstalls = false))
        assertEquals(InstallDecision.NeedPermission, installDecision(36, canRequestInstalls = false))
        assertEquals(InstallDecision.Install, installDecision(30, canRequestInstalls = true))
    }
}
