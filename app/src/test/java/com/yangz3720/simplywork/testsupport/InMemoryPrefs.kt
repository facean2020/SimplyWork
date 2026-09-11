package com.yangz3720.simplywork.testsupport

import android.content.SharedPreferences

/**
 * JVM 单测用的 [SharedPreferences] 内存桩：只为纯键值语义的存储壳（ProjectStore 等）
 * 提供行为。所有非 String 操作是显式 no-op/默认值 —— 出现即测试桩越界。
 */
class InMemoryPrefs : SharedPreferences {
    private val map = HashMap<String, String>()

    override fun getString(key: String?, defValue: String?): String? = map[key] ?: defValue
    override fun edit(): SharedPreferences.Editor = object : SharedPreferences.Editor {
        private fun self() = this
        override fun putString(key: String?, value: String?) = self().also { map[key!!] = value.orEmpty() }
        override fun putStringSet(key: String?, values: MutableSet<String>?) = self()
        override fun putInt(key: String?, value: Int) = self()
        override fun putLong(key: String?, value: Long) = self()
        override fun putFloat(key: String?, value: Float) = self()
        override fun putBoolean(key: String?, value: Boolean) = self()
        override fun remove(key: String?) = self()
        override fun clear() = self()
        override fun commit() = true
        override fun apply() {}
    }
    override fun getAll(): MutableMap<String, *> = map
    override fun getInt(key: String?, defValue: Int) = defValue
    override fun getLong(key: String?, defValue: Long) = defValue
    override fun getFloat(key: String?, defValue: Float) = defValue
    override fun getBoolean(key: String?, defValue: Boolean) = defValue
    override fun getStringSet(key: String?, defValues: MutableSet<String>?) = defValues
    override fun contains(key: String?) = map.containsKey(key)
    override fun registerOnSharedPreferenceChangeListener(listener: SharedPreferences.OnSharedPreferenceChangeListener?) {}
    override fun unregisterOnSharedPreferenceChangeListener(listener: SharedPreferences.OnSharedPreferenceChangeListener?) {}
}