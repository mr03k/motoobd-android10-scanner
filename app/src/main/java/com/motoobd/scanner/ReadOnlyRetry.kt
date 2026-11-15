package com.motoobd.scanner

/** A completed NO DATA reply can be retried once. Exceptions, negatives and writes never retry. */
object ReadOnlyRetry {
    data class Result(val raw: String, val attempts: List<String>)
    fun execute(request: String, exchange: () -> String): Result {
        val attempts = mutableListOf(exchange())
        val mode = request.take(2).toIntOrNull(16)
        val lines = attempts[0].replace('>', '\n').split('\r', '\n').map { it.trim().uppercase() }
            .filter { it.isNotEmpty() && it.replace(" ", "") != request.uppercase().replace(" ", "") }
        if (mode in listOf(1, 2, 3, 6, 7, 9, 10) && lines == listOf("NO DATA")) attempts.add(exchange())
        return Result(attempts.last(), attempts.toList())
    }
}
