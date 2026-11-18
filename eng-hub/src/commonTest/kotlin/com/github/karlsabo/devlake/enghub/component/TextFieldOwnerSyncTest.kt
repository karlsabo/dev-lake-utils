package com.github.karlsabo.devlake.enghub.component

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class TextFieldOwnerSyncTest {

    @Test
    fun laggingOwnerEchoesDoNotOverwriteFasterTyping() {
        val sync = TextFieldOwnerSync("166")

        assertTrue(sync.recordLocalText("1668"))
        assertNull(sync.acceptOwnerValue("166"))
        assertTrue(sync.recordLocalText("16685"))
        assertTrue(sync.recordLocalText("166852"))

        assertNull(sync.acceptOwnerValue("1668"))
        assertNull(sync.acceptOwnerValue("16685"))
        assertNull(sync.acceptOwnerValue("166852"))
    }

    @Test
    fun genuineOwnerChangesReplaceFieldText() {
        val sync = TextFieldOwnerSync("draft")
        sync.recordLocalText("draft command")

        assertEquals("", sync.acceptOwnerValue(""))
        assertFalse(sync.recordLocalText(""))
        assertTrue(sync.recordLocalText("next"))
    }

    @Test
    fun initialAndAlreadyReportedTextIsNotReportedAgain() {
        val sync = TextFieldOwnerSync("main")

        assertFalse(sync.recordLocalText("main"))
        assertTrue(sync.recordLocalText("main-2"))
        assertFalse(sync.recordLocalText("main-2"))
    }
}
