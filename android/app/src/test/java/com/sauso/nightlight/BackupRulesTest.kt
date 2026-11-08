package com.sauso.nightlight

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.w3c.dom.Element
import java.io.File
import javax.xml.parsers.DocumentBuilderFactory

/**
 * The saved sign-in must stay OUT of every copy of the app's data (plan phase D, review of Codex and Opus). Android 12+ reads
 * `dataExtractionRules`, where the cloud backup and the phone-to-phone transfer are separate sections and a section that is
 * left out is copied whole; Android 6-11 reads `fullBackupContent`. So the test reads the real files and checks the manifest
 * points at both, BOTH sections of the first exclude the vault's file in the `sharedpref` domain, and the second does too.
 * The file name is built from [SessionVault.PREFS_FILE], so renaming the vault without the rules fails here.
 *
 * The web app's own storage (`app_webview`, the WebView data folder, which holds the web sign-in token of each address) is kept out
 * of the same copies (owner, 2026-11-07): it is the `root` domain, path `app_webview`, in every section the vault is excluded from.
 */
class BackupRulesTest {

    private val androidNs = "http://schemas.android.com/apk/res/android"
    private val excluded = SessionVault.PREFS_FILE + ".xml"
    private val webStorage = "root" to "app_webview"

    // Unit tests run with the module (android/app) as the working directory; Gradle from the repo root has been seen to differ.
    private fun file(relative: String): File {
        val here = File(relative)
        return if (here.exists()) here else File("android/app/$relative")
    }

    private fun parse(relative: String): Element {
        val f = file(relative)
        assertTrue("missing: $relative", f.exists())
        val factory = DocumentBuilderFactory.newInstance().apply { isNamespaceAware = true }
        return factory.newDocumentBuilder().parse(f).documentElement
    }

    private fun excludes(section: Element): List<Pair<String, String>> {
        val out = mutableListOf<Pair<String, String>>()
        val nodes = section.getElementsByTagName("exclude")
        for (i in 0 until nodes.length) {
            val e = nodes.item(i) as Element
            out.add(e.getAttribute("domain") to e.getAttribute("path"))
        }
        return out
    }

    @Test
    fun `the manifest points at both rule files`() {
        val manifest = parse("src/main/AndroidManifest.xml")
        val app = manifest.getElementsByTagName("application").item(0) as Element
        assertEquals("@xml/data_extraction_rules", app.getAttributeNS(androidNs, "dataExtractionRules"))
        assertEquals("@xml/backup_rules", app.getAttributeNS(androidNs, "fullBackupContent"))
    }

    @Test
    fun `Android 12 rules exclude the vault and the web storage from BOTH the cloud backup and the device transfer`() {
        val root = parse("src/main/res/xml/data_extraction_rules.xml")
        assertEquals("data-extraction-rules", root.tagName)
        for (section in listOf("cloud-backup", "device-transfer")) {
            val nodes = root.getElementsByTagName(section)
            assertEquals("$section must be present exactly once", 1, nodes.length)
            assertTrue("$section does not exclude the vault in the sharedpref domain: ${excludes(nodes.item(0) as Element)}",
                excludes(nodes.item(0) as Element).contains("sharedpref" to excluded))
            assertTrue("$section does not exclude the web storage: ${excludes(nodes.item(0) as Element)}",
                excludes(nodes.item(0) as Element).contains(webStorage))
        }
    }

    @Test
    fun `Android 6 to 11 rules exclude the vault and the web storage`() {
        val root = parse("src/main/res/xml/backup_rules.xml")
        assertEquals("full-backup-content", root.tagName)
        assertTrue(excludes(root).contains("sharedpref" to excluded))
        assertTrue(excludes(root).contains(webStorage))
    }

    @Test
    fun `the rules exclude nothing else, so the saved server list and the other settings keep being backed up as before`() {
        val all = excludes(parse("src/main/res/xml/data_extraction_rules.xml")) + excludes(parse("src/main/res/xml/backup_rules.xml"))
        assertTrue(all.isNotEmpty())
        assertTrue("an unexpected exclusion: $all", all.all { it == ("sharedpref" to excluded) || it == webStorage })
        assertNotNull(SessionVault.PREFS_FILE)
        assertEquals("nightlight_session", SessionVault.PREFS_FILE)
    }
}
