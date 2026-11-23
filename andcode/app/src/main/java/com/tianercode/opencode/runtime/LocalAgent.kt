package com.tianercode.opencode.runtime












enum class LocalAgent(
    val id: String,
) {
    OPEN_CODE("opencode"),
    ;

    companion object {
        fun fromId(id: String): LocalAgent? = entries.firstOrNull { it.id == id }
    }
}
