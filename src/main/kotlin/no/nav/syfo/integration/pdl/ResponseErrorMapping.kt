package no.nav.syfo.integration.pdl

import no.nav.syfo.platform.upstream.UpstreamError

private const val MAX_ERROR_MESSAGE_LENGTH = 200

// PDL documents that messages on its own errors (those with extensions.code) contain no personal data.
internal fun ResponseError.toUpstreamError(): UpstreamError = UpstreamError(
    code = extensions?.code ?: "unknown",
    detail = extensions?.id,
    path = path?.joinToString("."),
    message = message?.takeIf { extensions?.code != null }?.take(MAX_ERROR_MESSAGE_LENGTH),
)
