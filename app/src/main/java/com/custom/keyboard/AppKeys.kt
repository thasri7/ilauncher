package com.custom.keyboard

import android.content.Context
import android.os.Process
import android.os.UserHandle
import android.os.UserManager

/**
 * Apps are identified by package name, and copies made by the phone's "App clone" / "Dual apps"
 * (or a work profile) by "package#profile", where profile is the profile's serial number. Tiles,
 * usage, notifications and icons all use this key, so a clone is a separate app everywhere.
 */
object AppKeys {
    fun isClone(key: String?) = key != null && '#' in key

    fun pkg(key: String): String = key.substringBefore('#')

    fun user(context: Context, key: String): UserHandle {
        if (!isClone(key)) return Process.myUserHandle()
        val serial = key.substringAfter('#').toLongOrNull() ?: return Process.myUserHandle()
        return context.getSystemService(UserManager::class.java)?.getUserForSerialNumber(serial) ?: Process.myUserHandle()
    }

    fun keyFor(context: Context, pkg: String, user: UserHandle?): String {
        if (user == null || user == Process.myUserHandle()) return pkg
        val serial = context.getSystemService(UserManager::class.java)?.getSerialNumberForUser(user) ?: return pkg
        return "$pkg#$serial"
    }
}
