package com.speedread.rsvp.engine

sealed class RsvpState {
    object Idle : RsvpState()
    object Playing : RsvpState()
    object Paused : RsvpState()
    object Finished : RsvpState()
}