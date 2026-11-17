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
}
