package com.linan.barezen_drive

interface Platform {
    val name: String
}

expect fun getPlatform(): Platform