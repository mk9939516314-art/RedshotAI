package app.redshot

import org.junit.Assert.*
import org.junit.Before
import org.junit.Test

class ConvoTest {
    @Before fun reset() { Convo.clear(); AwayLog.drain() }

    @Test fun threadKeepsOrderAndSpeakers() {
        Convo.add("Papa ji", "them", "beta khana khaya?")
        Convo.add("Papa ji", "me", "Mayank abhi class me hai, aate hi bata dunga")
        Convo.add("Papa ji", "them", "theek hai")
        val t = Convo.thread("papa JI")
        assertEquals(3, t.size)
        assertEquals("Papa ji: beta khana khaya?", t[0])
        assertTrue(t[1].startsWith("Redshot: "))
        assertEquals("Papa ji: theek hai", t[2])
    }

    @Test fun sameNotificationTwiceIsOneLine() {
        Convo.add("Rahul", "them", "bhai kal milte hain")
        Convo.add("Rahul", "them", "bhai kal milte hain")
        assertEquals(1, Convo.thread("Rahul").size)
    }

    @Test fun peopleAreSeparateAndThreadsAreBounded() {
        Convo.add("Rahul", "them", "hi")
        Convo.add("Mummy", "them", "kahan ho")
        assertEquals(1, Convo.thread("Rahul").size)
        for (i in 1..40) Convo.add("Rahul", "them", "msg $i")
        val t = Convo.thread("Rahul")
        assertEquals(14, t.size)
        assertEquals("Rahul: msg 40", t.last())
        assertTrue(Convo.thread("Nobody").isEmpty())
    }

    @Test fun blankIsIgnored() {
        Convo.add("", "them", "x"); Convo.add("A", "them", "  ")
        assertTrue(Convo.thread("A").isEmpty())
    }

    @Test fun awayLogCollectsThenDrainsOnce() {
        AwayLog.add("Papa ji ne likha: call kar")
        AwayLog.add("Papa ji ko jawab diya: abhi busy hai")
        assertEquals(2, AwayLog.size())
        val first = AwayLog.peek().lines()
        assertTrue(first[0].contains("Papa ji ne likha"))
        assertTrue(first[1].contains("jawab diya"))
        val drained = AwayLog.drain()
        assertTrue(drained.contains("call kar"))
        assertEquals(0, AwayLog.size())
        assertEquals("", AwayLog.drain())
    }

    @Test fun awayLogIsCapped() {
        for (i in 1..120) AwayLog.add("event $i")
        assertEquals(80, AwayLog.size())
        assertTrue(AwayLog.peek().contains("event 120"))
        assertFalse(AwayLog.peek().contains("event 1\n"))
    }
}
