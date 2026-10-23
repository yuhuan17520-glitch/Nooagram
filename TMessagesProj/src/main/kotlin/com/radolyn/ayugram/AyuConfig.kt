package com.radolyn.ayugram

import android.content.SharedPreferences
import android.util.Base64
import org.telegram.messenger.ApplicationLoader
import tw.nekomimi.nekogram.NekoConfig
import com.radolyn.ayugram.config.ConfigItem
import com.radolyn.ayugram.config.ConfigItemKeyLinked
import java.io.ByteArrayInputStream
import java.io.ObjectInputStream

/** Ayu-only compatibility settings; persisted keys match Nooagram 1.2.3. */
object AyuConfig {
    val sync = Any()
    private var configLoaded = false
    private val configs = ArrayList<ConfigItem>()
    fun init() { loadConfig(false) }
    val saveToChatSubfolder = addConfig("SaveToChatSubfolder", ConfigItem.configTypeBool, false)
    val chatMenuItemViewDeleted = addConfig("ChatMenuItemViewDeleted", ConfigItem.configTypeBool, true)
    val chatMenuItemClearDeleted = addConfig("ChatMenuItemClearDeleted", ConfigItem.configTypeBool, true)
    val attachmentFolderPath =
        addConfig(
            "AttachmentFolderPath",
            ConfigItem.configTypeString,
            ""
        )

    val attachmentFolderSizeLimitPreset =
        addConfig(
            "AttachmentFolderSizeLimitPreset",
            ConfigItem.configTypeInt,
            3
        )

    val blockedChannelsData =
        addConfig(
            "BlockedChannelsData",
            ConfigItem.configTypeString,
            "[]"
        )

    val customDeletedMark =
        addConfig(
            "CustomDeletedMark",
            ConfigItem.configTypeString,
            ""
        )

    val customFilteredUsersData =
        addConfig(
            "CustomFilteredUsersData",
            ConfigItem.configTypeString,
            "[]"
        )

    val deletedIconColor =
        addConfig(
            "DeletedIconColor",
            ConfigItem.configTypeInt,
            0
        )

    val deletedIconStyle =
        addConfig(
            "DeletedIconStyle",
            ConfigItem.configTypeInt,
            -1
        )

    val enableSaveDeletedMessages =
        addConfig(
            "EnableSaveDeletedMessages",
            ConfigItem.configTypeBool,
            false
        )

    val enableSaveEditsHistory =
        addConfig(
            "EnableSaveEditsHistory",
            ConfigItem.configTypeBool,
            false
        )

    val messageSavingSaveMedia =
        addConfig(
            "MessageSavingSaveMedia",
            ConfigItem.configTypeBool,
            true
        )

    val regexChatFiltersData =
        addConfig(
            "RegexChatFiltersData",
            ConfigItem.configTypeString,
            "[]"
        )

    val regexFiltersData =
        addConfig(
            "RegexFiltersData",
            ConfigItem.configTypeString,
            "[]"
        )

    val regexFiltersEnableInChats =
        addConfig(
            "RegexFiltersEnableInChats",
            ConfigItem.configTypeBool,
            false
        )

    val regexFiltersEnabled =
        addConfig(
            "RegexFilters",
            ConfigItem.configTypeBool,
            false
        )

    val regexFiltersExcludedDialogs =
        addConfig(
            "RegexFiltersExcludedDialogs",
            ConfigItem.configTypeString,
            "[]"
        )

    val regexFiltersExcludedEntriesData =
        addConfig(
            "RegexFiltersExcludedEntriesData",
            ConfigItem.configTypeString,
            "[]"
        )

    val regexFiltersHideOnlyMatched =
        addConfig(
            "RegexFiltersHideOnlyMatched",
            ConfigItem.configTypeBool,
            false
        )

    val regexFiltersLastImportLink =
        addConfig(
            "RegexFiltersLastImportLink",
            ConfigItem.configTypeString,
            ""
        )

    val regexFiltersMaskMessages =
        addConfig(
            "RegexFiltersMaskMessages",
            ConfigItem.configTypeBool,
            false
        )

    val saveDeletedMessageForBot =
        addConfig(
            "SaveDeletedMessageForBot", // save in bot chats
            ConfigItem.configTypeBool,
            false
        )

    val saveDeletedMessageForBotUser =
        addConfig(
            "SaveDeletedMessageForBotUser", // all messages from bot
            ConfigItem.configTypeBool,
            false
        )

    val saveLocalLastSeen =
        addConfig(
            "SaveLocalLastSeen",
            ConfigItem.configTypeBool,
            false
        )

    val saveMediaInPrivateChannels =
        addConfig(
            "SaveMediaInPrivateChannels",
            ConfigItem.configTypeBool,
            true
        )

    val saveMediaInPrivateChats =
        addConfig(
            "SaveMediaInPrivateChats",
            ConfigItem.configTypeBool,
            true
        )

    val saveMediaInPrivateGroups =
        addConfig(
            "SaveMediaInPrivateGroups",
            ConfigItem.configTypeBool,
            true
        )

    val saveMediaInPublicChannels =
        addConfig(
            "SaveMediaInPublicChannels",
            ConfigItem.configTypeBool,
            true
        )

    val saveMediaInPublicGroups =
        addConfig(
            "SaveMediaInPublicGroups",
            ConfigItem.configTypeBool,
            true
        )

    val saveMediaOnCellularDataLimit =
        addConfig(
            "SaveMediaOnCellularDataLimit",
            ConfigItem.configTypeLong,
            16L * 1024L * 1024L
        )

    val saveMediaOnWiFiLimit =
        addConfig(
            "SaveMediaOnWiFiLimit",
            ConfigItem.configTypeLong,
            64L * 1024L * 1024L
        )

    val saveReadDate =
        addConfig(
            "SaveReadDate",
            ConfigItem.configTypeBool,
            false
        )

    val sectionsSeparatedHeaders =
        addConfig(
            "sectionsSeparatedHeaders",
            ConfigItem.configTypeBool,
            true
        )

    val translatorMode =
        addConfig(
            "TranslatorMode",
            ConfigItem.configTypeInt,
            0 // 0: off; 1: manual only; 2: all
        )

    val translucentDeletedMessages =
        addConfig(
            "TranslucentDeletedMessages",
            ConfigItem.configTypeBool,
            true
        )
    private fun addConfig(
        k: String, t: Int, d: Any?
    ): ConfigItem {
        val a = ConfigItem(
            k, t, d
        )
        configs.add(
            a
        )
        return a
    }

    @Suppress("SameParameterValue")
    private fun addConfig(
        k: String, t: ConfigItem, d: Int, e: Any?
    ): ConfigItemKeyLinked {
        val a = ConfigItemKeyLinked(
            k,
            t,
            d,
            e,
        )
        configs.add(
            a
        )
        return a
    }

    fun loadConfig(
        force: Boolean
    ) {
        synchronized(
            sync
        ) {
            if (configLoaded && !force) {
                return
            }
            if (ApplicationLoader.applicationContext == null) {
                return
            }
            for (i in configs.indices) {
                val o = configs[i]
                if (o.type == ConfigItem.configTypeBool) {
                    o.value = NekoConfig.preferences.getBoolean(
                        o.key, o.defaultValue as Boolean
                    )
                }
                if (o.type == ConfigItem.configTypeInt) {
                    o.value = NekoConfig.preferences.getInt(
                        o.key, o.defaultValue as Int
                    )
                }
                if (o.type == ConfigItem.configTypeLong) {
                    o.value = NekoConfig.preferences.getLong(
                        o.key, (o.defaultValue as Long)
                    )
                }
                if (o.type == ConfigItem.configTypeFloat) {
                    o.value = NekoConfig.preferences.getFloat(
                        o.key, (o.defaultValue as Float)
                    )
                }
                if (o.type == ConfigItem.configTypeString) {
                    o.value = NekoConfig.preferences.getString(
                        o.key, o.defaultValue as String
                    )
                }
                if (o.type == ConfigItem.configTypeSetInt) {
                    val ss = NekoConfig.preferences.getStringSet(
                        o.key, HashSet()
                    )
                    val si = HashSet<Int>()
                    for (s in ss!!) {
                        si.add(
                            s.toInt()
                        )
                    }
                    o.value = si
                }
                if (o.type == ConfigItem.configTypeMapIntInt) {
                    val cv = NekoConfig.preferences.getString(
                        o.key, ""
                    )
                    // Log.e("NC", String.format("Getting pref %s val %s", o.key, cv));
                    if (cv!!.isEmpty()) {
                        o.value = HashMap<Int, Int>()
                    } else {
                        try {
                            val data = Base64.decode(
                                cv, Base64.DEFAULT
                            )
                            val ois = ObjectInputStream(
                                ByteArrayInputStream(
                                    data
                                )
                            )
                            o.value = ois.readObject() as HashMap<*, *>
                            if (o.value == null) {
                                o.value = HashMap<Int, Int>()
                            }
                            ois.close()
                        } catch (_: Exception) {
                            o.value = HashMap<Int, Int>()
                        }
                    }
                }
                if (o.type == ConfigItem.configTypeBoolLinkInt) {
                    o as ConfigItemKeyLinked
                    o.changedFromKeyLinked(NekoConfig.preferences.getInt(o.keyLinked.key, o.keyLinked.defaultValue as Int))
                }
            }
            configLoaded = true
        }
    }

    // Keep a strong reference: SharedPreferences stores listeners weakly.
    private val preferencesListener = SharedPreferences.OnSharedPreferenceChangeListener { _, key ->
        if (key == null || configs.any { it.key == key }) loadConfig(true)
        tw.nekomimi.nekogram.filters.AyuFilter.onPreferencesChanged(key)
    }

    init {
        loadConfig(false)
        NekoConfig.preferences.registerOnSharedPreferenceChangeListener(preferencesListener)
    }
}
