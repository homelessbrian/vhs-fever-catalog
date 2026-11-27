package org.vhsfever.tv

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Test

class Code128Test {
    @Test fun eachSymbolIsElevenModules() {
        val text = "tt0085334"
        // start + one per character + check digit, 11 modules each, plus the 13-module stop
        assertEquals(11 * (text.length + 2) + 13, Code128.modules(text))
        assertEquals(6 * (text.length + 2) + 7, Code128.widths(text).size)
    }

    @Test fun differentMoviesGetDifferentBarcodes() {
        assertNotEquals(Code128.widths("tt0085334"), Code128.widths("tt0076759"))
    }

    @Test fun knownCheckValue() {
        // "AB" in set B: (104 + 1*33 + 2*34) % 103 = 102 → pattern 411131
        val w = Code128.widths("AB").joinToString("")
        assertEquals("211214" + "111323" + "131123" + "411131" + "2331112", w)
    }
}
