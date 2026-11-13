package io.github.timhub135.dsh.core.session

/**
 * How `session/prompt` opens a conversation in the engine. The SDK server creates a session it does not
 * have open; a session opened in an earlier engine generation must be resumed (`session/resume`) first,
 * which SessionStore does when the app logged any of its events. One whose events never reached the app
 * (the engine went away right after opening it) is not resumed, and dsh refuses to create it again:
 * `session "<id>" already exists` (#57). That error is answered by resuming the session and prompting again.
 */
object SessionOpen {
    private val ALREADY_EXISTS = Regex("""^session "[^"]+" already exists$""")

    /** Whether [message] is dsh's refusal to create a session that is already stored. */
    fun alreadyExists(message: String?): Boolean = message != null && ALREADY_EXISTS.matches(message.trim())
}
