package com.homeflix.app.util

import com.homeflix.app.data.Resume
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ResumeRulesTest {
    @Test
    fun `no resume means start from the beginning`() {
        assertNull(ResumeRules.resumePosition(null))
        assertFalse(ResumeRules.inProgress(null))
    }

    @Test
    fun `first thirty seconds are ignored`() {
        assertNull(ResumeRules.resumePosition(Resume(time = 30.0, duration = 600.0)))
        assertNull(ResumeRules.resumePosition(Resume(time = 10.0, duration = 600.0)))
    }

    @Test
    fun `the last two minutes are treated as finished`() {
        val resume = Resume(time = 500.0, duration = 600.0)
        assertNull(ResumeRules.resumePosition(resume))
        assertTrue(ResumeRules.finished(resume))
    }

    @Test
    fun `a position in the middle resumes`() {
        val resume = Resume(time = 120.0, duration = 600.0)
        assertEquals(120.0, ResumeRules.resumePosition(resume)!!, 0.001)
        assertTrue(ResumeRules.inProgress(resume))
        assertEquals(0.2f, ResumeRules.percent(resume), 0.001f)
    }

    @Test
    fun `unknown duration cannot be resumed`() {
        assertNull(ResumeRules.resumePosition(Resume(time = 120.0, duration = 0.0)))
        assertEquals(0f, ResumeRules.percent(Resume(time = 120.0, duration = 0.0)), 0.001f)
    }
}
