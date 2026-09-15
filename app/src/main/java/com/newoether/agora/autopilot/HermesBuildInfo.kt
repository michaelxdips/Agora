package com.newoether.agora.autopilot

/**
 * Identity of this build, in one place.
 *
 * The phone-side twin of `com.newoether.agora.wear.WearBuildInfo`: both screens that name the
 * product (About here, Debug on the watch) read a constant instead of a literal, so a rename can
 * never leave one of them stale.
 *
 * **Maintainer: Michael** — this is a fork of Agora. Upstream Agora stays the upstream authors'
 * work; see `NOTICE.md`. These are product-identity constants, not a licence claim.
 */
object HermesBuildInfo {

    /** Display name of this fork. */
    const val PRODUCT_NAME = "Hermes X"

    /** Maintainer of this fork. Surfaced in Settings → About. */
    const val MAINTAINER = "Michael"

    /** Upstream project this fork is based on. */
    const val UPSTREAM_URL = "github.com/newo-ether/Agora"

    /** Fork repository. */
    const val FORK_URL = "github.com/michaelxdips/Agora"

    /** The three lines the About screen shows, so the phone and watch screens cannot disagree. */
    fun identityLines(): String =
        "Maintainer: $MAINTAINER\nFork of Agora — $UPSTREAM_URL\n$FORK_URL"
}
