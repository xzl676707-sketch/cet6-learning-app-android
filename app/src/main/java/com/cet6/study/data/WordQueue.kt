package com.cet6.study.data

/** Continue after the opened item; earlier items and retries follow the remaining suffix. */
fun <T> nextQueueItem(previous: List<T>, current: T, remaining: List<T>): T? {
    val index = previous.indexOf(current)
    if (index < 0) return remaining.firstOrNull()
    val available = remaining.toSet()
    return (previous.drop(index + 1) + previous.take(index + 1))
        .firstOrNull { it in available } ?: remaining.firstOrNull()
}
