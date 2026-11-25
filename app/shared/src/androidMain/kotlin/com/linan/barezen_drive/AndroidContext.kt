package com.linan.barezen_drive

import android.app.Activity
import android.content.Context

object AndroidContext {
    lateinit var app: Context
        private set

    var activity: Activity? = null
        private set

    fun setActivity(activity: Activity?) { this.activity = activity }

    fun init(ctx: Context) {
        app = ctx.applicationContext
    }
}
