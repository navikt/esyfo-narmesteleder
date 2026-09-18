package no.nav.syfo.organisasjonstilgang.application

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.string.shouldNotContain
import io.kotest.matchers.string.shouldStartWith
import no.nav.syfo.ident.PersonIdent

class OrganizationAccessSubjectTest : FunSpec({
    test("redacts access tokens from string representations") {
        val token = AccessToken("sensitive-access-token")

        token.toString() shouldStartWith "AccessToken("
        token.toString() shouldNotContain "sensitive-access-token"
        OrganizationAccessSubject.PersonnelManager(
            personIdent = PersonIdent("12345678901"),
            accessToken = token,
        ).toString() shouldNotContain "sensitive-access-token"
    }
})
