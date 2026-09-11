package com.linan.barezen_drive.core.dto

import kotlinx.serialization.Serializable

/** One registered account in the owner's user management list. */
@Serializable data class AdminUserDto(
    val id: String,
    val username: String,
    val createdAt: String,
    val fileCount: Long = 0,
)

@Serializable data class AdminUsersResponse(val users: List<AdminUserDto> = emptyList())
