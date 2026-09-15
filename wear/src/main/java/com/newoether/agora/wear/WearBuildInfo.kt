package com.newoether.agora.wear

/**
 * Identity of this build, in one place.
 *
 * The display name lives here and nowhere else on the watch so the rename cannot drift between
 * screens (the setup header and the chat header used two different literals before).
 *
 * **Maintainer: Michael** — this module is part of the Hermes fork of Agora; upstream Agora is the
 * upstream authors' work (see `NOTICE.md`). These are product-identity constants, not a licence
 * claim.
 */
object WearBuildInfo {

    /** Display name, shown in the chat header and the setup header. */
    const val PRODUCT_NAME = "Hermes X"

    /** Maintainer of this fork. Surfaced in the watch's Debug panel. */
    const val MAINTAINER = "Michael"

    /** Upstream project this fork is based on. */
    const val UPSTREAM_URL = "github.com/newo-ether/Agora"

    /** Fork repository. */
    const val FORK_URL = "github.com/michaelxdips/Agora"

    /** The three lines the Debug panel shows, so the phone and watch screens cannot disagree. */
    fun identityLines(): String =
        "Maintainer: $MAINTAINER\nFork of Agora — $UPSTREAM_URL\n$FORK_URL"
}
