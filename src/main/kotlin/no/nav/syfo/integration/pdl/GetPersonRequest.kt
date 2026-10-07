package no.nav.syfo.integration.pdl

data class GetPersonRequest(val query: String, val variables: GetPersonVariables)

data class GetPersonBolkRequest(val query: String, val variables: GetPersonBolkVariables)
