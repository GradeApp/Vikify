package vikify.vpn.diagnostics

import org.junit.Assert.*
import org.junit.Test

class DiagnosticReportTest {
    @Test fun schemaAndNumericalFieldsAreBoundedAndTimesRounded() {
        val event = DiagnosticEvent(DiagnosticStage.SUBSCRIPTION, DiagnosticStatus.FAIL,
            DiagnosticCode.SUBSCRIPTION_HTTP_ERROR, DiagnosticRoute.DIRECT, DiagnosticAction.RETRY_LATER,
            elapsedMs = 12345, httpStatus = 503, acceptedCount = 1000, skippedCount = Int.MAX_VALUE)
        val report = DiagnosticReportData(999, 25, 33, DiagnosticProtocol.XHTTP_TLS, listOf(event), true)
        val json = DiagnosticReport.toJson(report)
        assertTrue(json.startsWith("{\n  \"schemaVersion\":1,"))
        assertTrue(json.contains("\"elapsedMs\":12300"))
        assertTrue(json.contains("\"httpStatus\":503"))
        assertTrue(json.contains("\"acceptedCount\":100"))
        assertTrue(json.contains("\"skippedCount\":500"))
        assertFalse(json.contains("999"))
        assertEquals(1, Regex("\"stage\"").findAll(json).count())
    }

    @Test fun arbitraryValuesCannotBecomeIdentifiersOrUnboundedOutput() {
        val event = DiagnosticEvent(DiagnosticStage.LOCAL_FORMAT, DiagnosticStatus.NOT_TESTED,
            DiagnosticCode.NO_PROFILE, elapsedMs = Long.MAX_VALUE, httpStatus = Int.MAX_VALUE,
            acceptedCount = -1, skippedCount = -99)
        val report = DiagnosticReportData(appVersionCode = Int.MAX_VALUE, androidApi = -1,
            events = List(1000) { event })
        val json = DiagnosticReport.toJson(report)
        assertEquals(16, Regex("\"stage\"").findAll(json).count())
        assertTrue(json.contains("\"httpStatus\":null"))
        assertTrue(json.contains("\"elapsedMs\":180000"))
        assertTrue(json.contains("\"acceptedCount\":0"))
        assertFalse(json.contains(Long.MAX_VALUE.toString()))
        assertTrue(json.length < 6000)
        assertTrue(DiagnosticReport.toText(report).length < 6000)
    }
}
