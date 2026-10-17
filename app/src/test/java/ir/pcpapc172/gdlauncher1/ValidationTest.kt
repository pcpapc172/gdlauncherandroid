package ir.pcpapc172.gdlauncher1

import org.junit.Assert.*
import org.junit.Test

class ValidationTest {
    @Test
    fun generatedPackagesAreValidFixedLengthAndDeterministic() {
        val name = Instance.packageFor("server_id")
        assertEquals(24, name.length)
        assertTrue(Validation.packageName(name))
        assertEquals(name, Instance.packageFor("server_id"))
        assertNotEquals(name, Instance.packageFor("other"))
    }

    @Test
    fun rejectsUnsafeIdsAndUrls() {
        val base = Instance(id = "safe", name = "Test", pkg = Instance.packageFor("safe"))
        for (id in listOf("../escape", "/absolute", "a/b", "", "x".repeat(81))) assertThrows(
            IllegalArgumentException::class.java
        ) {
            Validation.check(base.copy(id = id))
        }
        for (url in
            listOf(
                "http://example.com/game.apk",
                "file:///data/data/app",
                "https://user:password@example.com/game.apk",
            )) assertThrows(IllegalArgumentException::class.java) {
            Validation.check(base.copy(url = url))
        }
        Validation.check(base.copy(url = "https://example.com/game.apk"))
    }

    @Test
    fun patchingRequires24CharactersButShortcutsDoNot() {
        val base = Instance(id = "safe", name = "Test", pkg = "com.example.app")
        assertThrows(IllegalArgumentException::class.java) { Validation.check(base) }
        Validation.check(base.copy(mode = "shortcut"))
    }

    @Test
    fun componentValidationAcceptsAndroidShorthand() {
        assertTrue(Validation.component("com.example.app/.MainActivity"))
        assertTrue(Validation.component("com.example.app/com.example.app.MainActivity"))
        assertFalse(Validation.component("broken"))
        assertFalse(Validation.component("com.example.app//MainActivity"))
    }

    @Test
    fun geodeIdentityIsSeparateAndStable() {
        val i = Instance(pkg = Instance.packageFor("test"))
        assertEquals(18, i.geodePackage.length)
        assertTrue(Validation.packageName(i.geodePackage))
        assertEquals(i.geodePackage, i.copy(name = "Renamed").geodePackage)
    }
}
