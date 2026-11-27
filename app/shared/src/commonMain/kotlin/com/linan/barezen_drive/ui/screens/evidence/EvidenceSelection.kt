package com.linan.barezen_drive.ui.screens.evidence

data class EvidenceSelection(
    val active: Boolean = false,
    val selectedKeys: Set<String> = emptySet(),
) {
    fun toggle(key: String): EvidenceSelection = if (active) {
        copy(selectedKeys = if (selectedKeys.contains(key)) selectedKeys - key else selectedKeys + key)
    } else {
        copy(active = true, selectedKeys = setOf(key))
    }

    fun selectAll(keys: Collection<String>): EvidenceSelection =
        copy(active = true, selectedKeys = keys.toSet())

    fun clear(): EvidenceSelection = EvidenceSelection()
}
