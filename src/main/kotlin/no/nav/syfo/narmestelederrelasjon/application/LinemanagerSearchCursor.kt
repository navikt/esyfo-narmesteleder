package no.nav.syfo.narmestelederrelasjon.application

import java.nio.ByteBuffer
import java.nio.charset.CodingErrorAction
import java.util.Base64
import kotlin.text.Charsets.UTF_8

data class LinemanagerSearchCursor(
    val firstName: String?,
    val lastName: String?,
    val id: Int,
)

fun String?.toLinemanagerSearchCursor(): Result<LinemanagerSearchCursor?> = runCatching {
    this?.let { cursor ->
        val cursorParts = Base64.getUrlDecoder()
            .decode(cursor)
            .toStrictUtf8String()
            .split(":")
        require(cursorParts.size == 4 && cursorParts.first() == LINEMANAGER_SEARCH_CURSOR_VERSION) {
            "Unsupported cursor format"
        }
        val id = cursorParts.last().toInt()
        require(id > 0) {
            "Cursor id must be positive"
        }
        LinemanagerSearchCursor(
            firstName = cursorParts[1].toCursorName(),
            lastName = cursorParts[2].toCursorName(),
            id = id,
        )
    }
}

fun LinemanagerSearchCursor.toOpaqueCursor(): String {
    require(id > 0) {
        "Cursor id must be positive"
    }
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

private const val LINEMANAGER_SEARCH_CURSOR_VERSION = "v2"
private const val CURSOR_NULL_NAME_FIELD = "n"
private const val CURSOR_STRING_NAME_FIELD_PREFIX = "s"

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
