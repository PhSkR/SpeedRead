package com.speedread.rsvp.ui

/**
 * Scroll lifecycle for PageViewActivity's RecyclerView. Replaces two ad-hoc Boolean flags
 * (`isNavigatingProgrammatically`, `isInitialLoad`) and a timer-based settling delay with
 * explicit, event-driven transitions.
 *
 *  Initializing -> Programmatic : first page submission issues the initial scroll
 *  Programmatic -> Idle         : RecyclerView reaches SCROLL_STATE_IDLE after a scroll
 *  Idle         -> Programmatic : scrollToPage() issues an activity-driven scroll
 *
 * User-swipe handling only runs in Idle; Initializing and Programmatic both ignore the
 * scroll listener's position update branch so spurious indices from intermediate layout
 * passes can't overwrite the intended page.
 */
internal sealed class PageScrollState {
    object Initializing : PageScrollState()
    object Programmatic : PageScrollState()
    object Idle : PageScrollState()
}
