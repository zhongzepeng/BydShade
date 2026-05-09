package amirz.shade.carplus.easycontrol

import java.util.concurrent.ConcurrentHashMap

object EasycontrolSessionStore {
    private val displayIds = ConcurrentHashMap<String, Int>()

    fun putDisplayId(sessionId: String, displayId: Int) {
        displayIds[sessionId] = displayId
    }

    fun takeDisplayId(sessionId: String): Int? = displayIds[sessionId]

    fun removeDisplayId(sessionId: String): Int? = displayIds.remove(sessionId)
}
