package isomeria.aichat.feature.backup

import isomeria.aichat.R
import org.junit.Assert.*
import org.junit.Test

class ExportPasswordTest {
    @Test fun unprotectedNoKeyExportRemainsAvailable() { assertNull(exportPasswordError(false, "", "")) }
    @Test fun includingKeysRequiresPassword() { assertEquals(R.string.export_password_too_short, exportPasswordError(true, "", "")) }
    @Test fun optionalPasswordStillRequiresMinimumLengthAndConfirmation() {
        assertEquals(R.string.export_password_too_short, exportPasswordError(false, "123", "123"))
        assertEquals(R.string.export_password_mismatch, exportPasswordError(false, "123456", "654321"))
        assertEquals(R.string.export_password_too_short, exportPasswordError(false, "", "123456"))
    }
    @Test fun validPasswordIsAcceptedWithAndWithoutKeys() {
        assertNull(exportPasswordError(false, "123456", "123456"))
        assertNull(exportPasswordError(true, "123456", "123456"))
    }
}
