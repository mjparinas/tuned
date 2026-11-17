package app.tuned.core

import kotlin.test.Test
import kotlin.test.assertEquals

class XtreamAccountTest {
    @Test
    fun goodLogin() {
        assertEquals(true, XtreamAccount.accepted("""{"user_info":{"username":"a","auth":1,"status":"Active"}}"""))
        assertEquals(true, XtreamAccount.accepted("""{"user_info":{"auth":"1"}}"""))
    }

    @Test
    fun rejectedLogin() {
        assertEquals(false, XtreamAccount.accepted("""{"user_info":{"auth":0}}"""))
    }

    @Test
    fun answerThatSaysNeither() {
        assertEquals(null, XtreamAccount.accepted("[]"))
        assertEquals(null, XtreamAccount.accepted("<html>Not found</html>"))
    }

    @Test
    fun readsWhetherEveryConnectionIsInUse() {
        assertEquals(true, XtreamAccount.connectionsFull("""{"user_info":{"active_cons":"1","max_connections":"1"}}"""))
        assertEquals(false, XtreamAccount.connectionsFull("""{"user_info":{"active_cons":0,"max_connections":2}}"""))
        assertEquals(false, XtreamAccount.connectionsFull("""{"user_info":{"active_cons":"0","max_connections":"0"}}"""))
        assertEquals(null, XtreamAccount.connectionsFull("""{"user_info":{"auth":1}}"""))
    }

    @Test
    fun readsTheAccountDetails() {
        val body =
            """{"user_info":{"username":"a","auth":1,"status":"Active","exp_date":"1798761600","is_trial":"0",""" +
                """"active_cons":"1","max_connections":"2"},"server_info":{"timezone":"Europe\/London"}}"""
        val info = XtreamAccount.info(body)!!
        assertEquals("Active", info.status)
        assertEquals(1_798_761_600_000L, info.expiresAt)
        assertEquals(1, info.activeConnections)
        assertEquals(2, info.maxConnections)
        assertEquals(false, info.full)
        assertEquals(false, info.trial)
    }

    @Test
    fun noEndDateAndFullAccount() {
        val info = XtreamAccount.info("""{"user_info":{"auth":1,"exp_date":null,"active_cons":2,"max_connections":2,"is_trial":"1"}}""")!!
        assertEquals(null, info.expiresAt)
        assertEquals(true, info.full)
        assertEquals(true, info.trial)
        assertEquals(null, XtreamAccount.info("""{"user_info":{"auth":0}}"""))
    }

    @Test
    fun countsDaysLeft() {
        val day = 86_400_000L
        assertEquals(12, XtreamAccount.daysLeft(12 * day + 5, 0))
        assertEquals(0, XtreamAccount.daysLeft(day - 1, 0))
        assertEquals(-1, XtreamAccount.daysLeft(0, 1))
    }
}
