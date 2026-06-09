package cc.colorcat.mvi.internal

import kotlinx.coroutines.channels.Channel

/**
 * Validates channel-like buffer capacity for K-MVI queue configs.
 *
 * Allowed values:
 * - Named constants in `[-2, 0]`: [Channel.BUFFERED], [Channel.CONFLATED], [Channel.RENDEZVOUS]
 * - Any positive `Int` (`> 0`), including [Channel.UNLIMITED]
 *
 * Author: ccolorcat
 * Date: 2026-05-24
 * GitHub: https://github.com/ccolorcat
 */
internal fun requireSupportedCapacity(name: String, capacity: Int) {
    val isNamedConst = capacity == Channel.BUFFERED || capacity == Channel.CONFLATED || capacity == Channel.RENDEZVOUS
    require(isNamedConst || capacity > 0) {
        "$name must be Channel.BUFFERED, Channel.CONFLATED, Channel.RENDEZVOUS, or any positive Int, but was $capacity"
    }
}
