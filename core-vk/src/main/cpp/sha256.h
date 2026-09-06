#pragma once

#include <cstddef>
#include <cstdint>
#include <string>

namespace amaral {

/**
 * SHA-256 of a rendered frame.
 *
 * Two drivers producing bit-identical output is the strongest statement the app
 * can make about correctness; it is also the strictest, since legitimate
 * precision differences break it. The exact hash is therefore recorded for
 * integrity and non-determinism detection, and the tolerant comparison lives on
 * the Kotlin side where the tolerance can be stated and argued about.
 */
std::string sha256Hex(const uint8_t* data, size_t length);

}  // namespace amaral
