package com.linan.barezen_drive.data.local

interface TokenStore {
    var baseUrl: String
    var accessToken: String?
    var refreshToken: String?
    fun clear()
}

expect object TokenStorage : TokenStore {
    override var baseUrl: String
    override var accessToken: String?
    override var refreshToken: String?
    override fun clear()
}
