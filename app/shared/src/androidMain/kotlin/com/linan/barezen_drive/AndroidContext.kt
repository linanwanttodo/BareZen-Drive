package com.linan.barezen_drive

import android.content.Context

object AndroidContext {
    lateinit var app: Context
        private set

    fun init(ctx: Context) {
        app = ctx.applicationContext
    }
}
