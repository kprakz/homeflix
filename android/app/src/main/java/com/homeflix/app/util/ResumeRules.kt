package com.homeflix.app.util

import com.homeflix.app.data.Resume

/** The same "where was I" rules as the web player. */
object ResumeRules {
    private const val MIN_START = 30.0
    private const val END_MARGIN = 120.0

    fun resumePosition(resume: Resume?): Double? {
        if (resume == null || resume.duration <= 0) return null
        if (resume.time <= MIN_START) return null
        if (resume.time >= resume.duration - END_MARGIN) return null
        return resume.time
    }

    fun inProgress(resume: Resume?): Boolean = resumePosition(resume) != null

    fun finished(resume: Resume?): Boolean =
        resume != null && resume.duration > 0 && resume.time >= resume.duration - END_MARGIN

    fun percent(resume: Resume?): Float {
        if (resume == null || resume.duration <= 0) return 0f
        return (resume.time / resume.duration).toFloat().coerceIn(0f, 1f)
    }
}
