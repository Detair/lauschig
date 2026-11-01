package io.wolftown.lauschig.absapi

import kotlinx.serialization.json.Json

/** Shared JSON configuration: the server adds fields freely, so unknown keys are ignored. */
val AbsJson: Json = Json {
    ignoreUnknownKeys = true
    explicitNulls = false
    coerceInputValues = true
}
