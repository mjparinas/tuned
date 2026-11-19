package com.tunedplayer.app.data

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.currentTime
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals

@OptIn(ExperimentalCoroutinesApi::class)
class ConnectivityTest {
    @Test
    fun `a moment between two networks is not reported as offline`() = runTest {
        val seen =
            flow {
                emit(true)
                emit(false) // Wi-Fi dropped...
                delay(1_000)
                emit(true) // ...and mobile data took over
            }.settled(3_000).toList()
        assertEquals(listOf(true), seen)
    }

    @Test
    fun `staying offline is reported after the wait`() = runTest {
        val times = mutableListOf<Pair<Boolean, Long>>()
        flow {
            emit(true)
            emit(false)
            delay(10_000)
            emit(true)
        }.settled(3_000).collect { times += it to currentTime }
        assertEquals(listOf(true to 0L, false to 3_000L, true to 10_000L), times)
    }

    @Test
    fun `starting offline shows at once`() = runTest {
        val times = mutableListOf<Pair<Boolean, Long>>()
        flow { emit(false) }.settled(3_000).collect { times += it to currentTime }
        assertEquals(listOf(false to 0L), times)
    }
}
