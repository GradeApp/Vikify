package vikify.vpn.core

import java.util.regex.Pattern

private val UUID_V4_PATTERN = Pattern.compile(
    "^[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[4][0-9a-fA-F]{3}-[89abAB][0-9a-fA-F]{3}-[0-9a-fA-F]{12}$"
)

/**
 * Returns true if the string is a strict UUID v4 (RFC 4122).
 * Does not log the value.
 */
fun isValidUuidV4(value: String): Boolean {
    if (value.length != 36) return false
    return UUID_V4_PATTERN.matcher(value).matches()
}
