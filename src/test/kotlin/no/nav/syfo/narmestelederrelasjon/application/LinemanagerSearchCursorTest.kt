package no.nav.syfo.narmestelederrelasjon.application

import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.result.shouldBeFailure
import io.kotest.matchers.shouldBe
import java.util.Base64

class LinemanagerSearchCursorTest :
    FunSpec({
        listOf(
            LinemanagerSearchCursor(firstName = "ø:ystein", lastName = "", id = 42),
            LinemanagerSearchCursor(firstName = null, lastName = null, id = 1),
            LinemanagerSearchCursor(firstName = "", lastName = "漢字:é", id = 2),
            LinemanagerSearchCursor(firstName = null, lastName = "", id = Int.MAX_VALUE),
        ).forEach { cursor ->
            test("round-trips $cursor") {
                LinemanagerSearchCursor.fromPageToken(cursor.toPageToken()).getOrThrow() shouldBe cursor
            }
        }

        listOf(
            LinemanagerSearchCursor(firstName = null, lastName = null, id = 1) to "djI6bjpuOjE",
            LinemanagerSearchCursor(firstName = "ola", lastName = "nordmann", id = 1) to "djI6c2IyeGg6c2JtOXlaRzFoYm00OjE",
        ).forEach { (cursor, token) ->
            test("encodes $cursor as the published v2 token $token") {
                cursor.toPageToken() shouldBe token
                LinemanagerSearchCursor.fromPageToken(token).getOrThrow() shouldBe cursor
            }
        }

        test("decodes an absent token to no cursor") {
            LinemanagerSearchCursor.fromPageToken(null).getOrThrow() shouldBe null
        }

        listOf(
            "" to "empty token",
            "invalid!" to "non-Base64url token",
            "djE6MQ" to "v1 token",
            token("v2:n:n:0") to "zero id",
            token("v2:n:n:-1") to "negative id",
            token("v2:n:n:2147483648") to "id above Int.MAX_VALUE",
            token("v2:n:n:1:extra") to "extra field",
            token("v2:x:n:1") to "unknown name field marker",
            token("v2:s_w:n:1") to "name that is not UTF-8",
            token("v2:s!:n:1") to "non-Base64url name",
            Base64.getUrlEncoder().encodeToString(byteArrayOf(0xff.toByte())) to "token that is not UTF-8",
        ).forEach { (raw, description) ->
            test("rejects $description") {
                LinemanagerSearchCursor.fromPageToken(raw).shouldBeFailure()
            }
        }

        listOf(0, -1).forEach { id ->
            test("refuses to create a cursor with id $id") {
                shouldThrow<IllegalArgumentException> {
                    LinemanagerSearchCursor(firstName = null, lastName = null, id = id)
                }
            }
        }
    })

private fun token(raw: String): String = Base64.getUrlEncoder().withoutPadding().encodeToString(raw.toByteArray(Charsets.UTF_8))
