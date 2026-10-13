package no.nav.syfo.integration.pdl

import no.nav.syfo.platform.upstream.UpstreamError

internal fun ResponseError.toUpstreamError(): UpstreamError = UpstreamError(
    code = extensions?.code ?: "unknown",
    detail = extensions?.id,
    path = path?.joinToString("."),
)
