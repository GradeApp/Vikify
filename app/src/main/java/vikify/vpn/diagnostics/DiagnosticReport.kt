package vikify.vpn.diagnostics

/** An allowlist serializer, not a scrubber for logs or exception messages. */
object DiagnosticReport {
    private fun safe(report: DiagnosticReportData) = report.copy(
        schemaVersion = 1,
        appVersionCode = report.appVersionCode.coerceIn(0, 1_000_000_000),
        androidApi = report.androidApi.coerceIn(0, 1000),
        events = report.events.take(16).map { event -> event.copy(
            elapsedMs = (event.elapsedMs.coerceIn(0, 180_000) / 100) * 100,
            httpStatus = event.httpStatus?.takeIf { it in 100..599 },
            acceptedCount = event.acceptedCount?.coerceIn(0, 100),
            skippedCount = event.skippedCount?.coerceIn(0, 500)
        ) }
    )

    fun toText(report: DiagnosticReportData): String {
        val data = safe(report)
        return buildString {
            append("Vikify diagnostic report\n")
            append("schemaVersion: 1\nappVersionCode: ${data.appVersionCode}\n")
            append("androidApi: ${data.androidApi}\nprotocol: ${data.protocol.name}\n")
            append("completed: ${data.completed}\n")
            for (event in data.events) {
                append("\n${event.stage.name}: ${event.status.name}\n")
                append("code: ${event.code.name}\nroute: ${event.route.name}\n")
                append("action: ${event.action.name}\nelapsedMs: ${event.elapsedMs}\n")
                event.httpStatus?.let { append("httpStatus: $it\n") }
                event.acceptedCount?.let { append("acceptedCount: $it\n") }
                event.skippedCount?.let { append("skippedCount: $it\n") }
            }
        }
    }

    // Values are exclusively fixed enum names, bounded numbers and booleans.
    // No Android JSON runtime is required, so this format is tested on the JVM.
    fun toJson(report: DiagnosticReportData): String {
        val data = safe(report)
        val events = data.events.joinToString(",\n") { e ->
            "    {\"stage\":\"${e.stage.name}\",\"status\":\"${e.status.name}\"," +
                "\"code\":\"${e.code.name}\",\"route\":\"${e.route.name}\"," +
                "\"action\":\"${e.action.name}\",\"elapsedMs\":${e.elapsedMs}," +
                "\"httpStatus\":${e.httpStatus},\"acceptedCount\":${e.acceptedCount}," +
                "\"skippedCount\":${e.skippedCount}}"
        }
        return "{\n  \"schemaVersion\":1,\n  \"appVersionCode\":${data.appVersionCode},\n" +
            "  \"androidApi\":${data.androidApi},\n  \"protocol\":\"${data.protocol.name}\",\n" +
            "  \"completed\":${data.completed},\n  \"events\":[\n$events\n  ]\n}\n"
    }
}
