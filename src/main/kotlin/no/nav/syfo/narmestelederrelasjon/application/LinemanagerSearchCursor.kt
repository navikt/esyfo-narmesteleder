package no.nav.syfo.narmestelederrelasjon.application

import java.nio.ByteBuffer
import java.nio.charset.CodingErrorAction
import java.util.Base64
import kotlin.text.Charsets.UTF_8

data class LinemanagerSearchCursor(
    val firstName: String?,
    val lastName: String?,
    val id: Int,
) {
    init {
        require(id > 0) {
            "Cursor id must be positive"
        }
    }

    fun toPageToken(): String {
        val cursor = listOf(
            LINEMANAGER_SEARCH_CURSOR_VERSION,
            firstName.toCursorNameField(),
            lastName.toCursorNameField(),
            id,
        ).joinToString(":")

        return Base64.getUrlEncoder()
            .withoutPadding()
            .encodeToString(cursor.toByteArray(UTF_8))
    }

    companion object {
        fun fromPageToken(pageToken: String?): Result<LinemanagerSearchCursor?> = runCatching {
            pageToken?.let { token ->
                val cursorParts = Base64.getUrlDecoder()
                    .decode(token)
                    .toStrictUtf8String()
                    .split(":")
                require(cursorParts.isSupportedCursorFormat()) {
                    "Unsupported cursor format"
                }
                val (_, firstName, lastName, id) = cursorParts
                LinemanagerSearchCursor(
                    firstName = firstName.toCursorName(),
                    lastName = lastName.toCursorName(),
                    id = id.toInt(),
                )
            }
        }
    }
}

private const val LINEMANAGER_SEARCH_CURSOR_VERSION = "v2"
private const val CURSOR_PART_COUNT = 4
private const val CURSOR_NULL_NAME_FIELD = "n"
private const val CURSOR_STRING_NAME_FIELD_PREFIX = "s"

private fun List<String>.isSupportedCursorFormat(): Boolean = size == CURSOR_PART_COUNT && first() == LINEMANAGER_SEARCH_CURSOR_VERSION

private fun String?.toCursorNameField(): String = this?.let {
    "$CURSOR_STRING_NAME_FIELD_PREFIX${Base64.getUrlEncoder().withoutPadding().encodeToString(it.toByteArray(UTF_8))}"
} ?: CURSOR_NULL_NAME_FIELD

private fun String.toCursorName(): String? = when {
    this == CURSOR_NULL_NAME_FIELD -> null
    startsWith(CURSOR_STRING_NAME_FIELD_PREFIX) -> Base64.getUrlDecoder()
        .decode(removePrefix(CURSOR_STRING_NAME_FIELD_PREFIX))
        .toStrictUtf8String()

    else -> error("Invalid cursor name")
}

private fun ByteArray.toStrictUtf8String(): String = UTF_8.newDecoder()
    .onMalformedInput(CodingErrorAction.REPORT)
    .onUnmappableCharacter(CodingErrorAction.REPORT)
    .decode(ByteBuffer.wrap(this))
    .toString()
