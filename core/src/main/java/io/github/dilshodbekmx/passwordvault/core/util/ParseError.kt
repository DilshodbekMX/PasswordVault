package io.github.dilshodbekmx.passwordvault.core.util

/**
 * Why a parser refused its input (architecture.md §3.1, §5.6): the offset in the input at which it
 * stopped, and a reason that is a constant from that parser's own fixed list, such as
 * "duplicate key". It never holds a piece of the input, because the input is a vault body, a lock
 * record or an import file, all of which hold account data. The parsers (`BodyCodec`, `StrictJson`,
 * `RecordCodec`, the importers) catch every exception of the libraries they use at their boundary
 * and return one of these instead.
 */
data class ParseError(val offset: Long, val reason: String)
