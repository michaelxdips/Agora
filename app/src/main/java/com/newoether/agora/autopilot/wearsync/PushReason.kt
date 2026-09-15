package com.newoether.agora.autopilot.wearsync

import androidx.annotation.StringRes
import com.newoether.agora.R

/**
 * What a config transfer attempt did.
 *
 * The reason is an enum, not a sentence, so the two consumers can each do the right thing with one
 * source of truth:
 *  * the **phone screen** resolves [stringRes] — a resource, so it can be translated and so the copy
 *    lives in `strings.xml` like every other string in the app;
 *  * the **wire ack** uses [wireText] — the watch has no access to the phone's resources, so the
 *    sentence has to travel as text.
 *
 * Before this existed, `WatchSync` returned a hardcoded English sentence that the screen displayed
 * directly. That worked, but it bypassed `strings.xml`, which left four `hermes_watch_*` resources
 * defined and unreferenced — a translation that would never be picked up and a copy of every sentence
 * in two places.
 */
enum class PushReason(@StringRes val stringRes: Int, val wireText: String) {

    NO_ENDPOINT(
        R.string.hermes_watch_no_endpoint,
        "No base URL or model selected. Configure a provider first.",
    ),

    NO_KEY(
        R.string.hermes_watch_no_key,
        "No API key configured for the selected model. Set it in Providers first.",
    ),

    PUSH_FAILED(
        R.string.hermes_watch_push_failed,
        "No watch reachable — open the app on the watch and try again.",
    ),

    CONFIG_ONLY(
        R.string.hermes_watch_config_only,
        "Config sent; the memory snapshot will follow when the watch is reachable.",
    ),

    PUSHED(
        R.string.hermes_watch_pushed,
        "Sent. The watch is standalone now.",
    ),

    /** The phone was still starting up when the watch asked. Not a failure — a retry will work. */
    STARTING_UP(
        R.string.hermes_watch_starting_up,
        "The phone is still starting up. Try again in a moment.",
    ),
}
