package ru.usbprint.domain.model

data class CompatibilityExportEnvironment(val androidRelease: String, val androidSdk: Int) {
    init {
        require(androidRelease.isNotBlank())
        require(androidSdk > 0)
    }
}

/** Public attachment format. Deliberately omits identity hash, notes, document data, URI, filename, and payload. */
object CompatibilityRecordJson {
    const val SCHEMA_VERSION = 1

    fun encode(profile: VerifiedPrinterProfile, environment: CompatibilityExportEnvironment): String = buildString {
        appendLine("{")
        appendLine("  \"schemaVersion\": $SCHEMA_VERSION,")
        appendLine("  \"appVersion\": ${profile.appVersion.json()},")
        appendLine("  \"android\": {")
        appendLine("    \"release\": ${environment.androidRelease.json()},")
        appendLine("    \"sdk\": ${environment.androidSdk}")
        appendLine("  },")
        appendLine("  \"printer\": {")
        appendLine("    \"manufacturer\": ${profile.manufacturer.jsonOrNull()},")
        appendLine("    \"model\": ${profile.model.jsonOrNull()},")
        appendLine("    \"vendorId\": ${profile.vendorId.toString(16).padStart(4, '0').json()},")
        appendLine("    \"productId\": ${profile.productId.toString(16).padStart(4, '0').json()}")
        appendLine("  },")
        appendLine("  \"reportedProtocols\": {")
        appendLine("    \"languages\": ${profile.reportedLanguages.map { it.name }.sorted().jsonArray()},")
        appendLine("    \"ippDocumentFormats\": ${profile.ippFormats.map(String::lowercase).sorted().jsonArray()}")
        appendLine("  },")
        appendLine("  \"backend\": ${profile.backend.name.json()},")
        appendLine("  \"encoderVersion\": ${profile.encoderVersions.getValue(profile.backend)},")
        appendLine("  \"settings\": {")
        appendLine("    \"paper\": ${profile.paper.name.json()},")
        appendLine("    \"resolution\": ${profile.resolution?.displayName.jsonOrNull()},")
        appendLine("    \"color\": ${profile.color.name.json()},")
        appendLine("    \"duplex\": ${profile.duplex.name.json()}")
        appendLine("  },")
        appendLine("  \"result\": {")
        appendLine("    \"status\": ${profile.status.name.json()},")
        appendLine("    \"outcome\": ${profile.result.name.json()},")
        appendLine("    \"issues\": ${profile.issues.map { it.name }.sorted().jsonArray()},")
        appendLine("    \"testedAtEpochMs\": ${profile.testedAtEpochMs},")
        appendLine("    \"observationCount\": ${profile.history.size}")
        appendLine("  }")
        appendLine("}")
    }

    private fun String?.jsonOrNull(): String = this?.json() ?: "null"
    private fun List<String>.jsonArray(): String = joinToString(prefix = "[", postfix = "]") { it.json() }
    private fun String.json(): String = buildString(length + 2) {
        append('"')
        this@json.forEach { character ->
            when (character) {
                '"' -> append("\\\"")
                '\\' -> append("\\\\")
                '\b' -> append("\\b")
                '\u000c' -> append("\\f")
                '\n' -> append("\\n")
                '\r' -> append("\\r")
                '\t' -> append("\\t")
                else -> if (character.code < 0x20) append("\\u${character.code.toString(16).padStart(4, '0')}") else append(character)
            }
        }
        append('"')
    }
}
