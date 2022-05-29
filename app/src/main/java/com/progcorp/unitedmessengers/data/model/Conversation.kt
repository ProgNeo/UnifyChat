package com.progcorp.unitedmessengers.data.model

import com.progcorp.unitedmessengers.App
import com.progcorp.unitedmessengers.interfaces.ICompanion
import com.progcorp.unitedmessengers.util.Constants
import kotlinx.coroutines.MainScope
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.last
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.mapNotNull
import kotlinx.coroutines.launch
import org.drinkless.td.libcore.telegram.TdApi
import org.json.JSONArray
import org.json.JSONObject
import java.io.Serializable

data class Conversation(
    val id: Long = 0,
    val companion: ICompanion? = null,
    var lastMessage: Message? = null,
    var unreadCount: Int = 0,
    var canWrite: Boolean = true,
    val messenger: Int = 0
) : Serializable {
    fun getIsOnline(): Boolean? {
        return when (companion) {
            is User -> {
                companion.isOnline
            }
            else -> {
                null
            }
        }
    }

    fun getLastOnline(): Long? {
        return if (companion is User) {
            companion.lastSeen
        } else null
    }

    fun getPhoto(): String? {
        return when (companion) {
            is User -> {
                companion.photo
            }
            is Chat -> {
                companion.photo
            }
            is Bot -> {
                companion.photo
            }
            else -> {
                null
            }
        }
    }

    suspend fun tgParseLastMessage(update: TdApi.UpdateChatLastMessage) {
        val repository = App.application.tgRepository

        if (update.lastMessage != null) {
            lastMessage = Message.tgParse(update.lastMessage!!)
            unreadCount = repository.getConversation(this.id).last().data?.unreadCount!!
        }
    }

    suspend fun tgParseNewMessage(update: TdApi.UpdateNewMessage) {
        val repository = App.application.tgRepository

        if (update.message != null) {
            lastMessage = Message.tgParse(update.message!!)
            unreadCount = repository.getConversation(this.id).last().data?.unreadCount!!
        }
    }

    suspend fun tgParseOnlineStatus(update: TdApi.UpdateUserStatus) {
        val repository = App.application.tgRepository

        val user = repository.getUser(update.userId).last().data
        companion as User

        companion.isOnline = user?.isOnline == true
        companion.lastSeen = user?.lastSeen!!
    }

    companion object {

        suspend fun vkParse(json: JSONObject, profiles: JSONArray?, groups: JSONArray?): Conversation {
            val conversation = json.getJSONObject("conversation")
            val peer = conversation.getJSONObject("peer")

            val id = peer.optLong("id")
            val type = peer.optString("type")
            var companion: ICompanion? = null
            when {
                type == "chat" -> {
                    companion = Chat.vkParse(conversation.getJSONObject("chat_settings"), id)
                }
                type == "user" && profiles != null -> {
                    for (i in 0 until profiles.length()) {
                        val profile = profiles.getJSONObject(i)
                        if (profile.getLong("id") == id) {
                            companion = User.vkParse(profile)
                            break
                        }
                    }
                }
                type == "group" && groups != null -> {
                    for (i in 0 until groups.length()) {
                        val group = groups.getJSONObject(i)
                        if (group.getLong("id") == -id) {
                            companion = Bot.vkParse(group)
                            break
                        }
                    }
                }
            }
            val lastMessage = Message.vkParse(json.getJSONObject("last_message"), profiles, groups)

            var unreadCount = conversation.optInt("unread_count")

            val canWrite = conversation.getJSONObject("can_write").getBoolean("allowed")

            if (conversation.getInt("in_read_cmid") > conversation.getInt("out_read_cmid")) {
                unreadCount = -1
            }

            return Conversation(id, companion, lastMessage, unreadCount, canWrite, Constants.Messenger.VK)
        }

        suspend fun tgParse(conversation: TdApi.Chat): Conversation? {
            val repository = App.application.tgRepository

            if (conversation.positions.isEmpty()) {
                return null
            }

            val id = conversation.id
            val companion: ICompanion?
            when(conversation.type.constructor) {
                TdApi.ChatTypePrivate.CONSTRUCTOR -> {
                    companion = repository.getUser(id).first().data
                }
                TdApi.ChatTypeBasicGroup.CONSTRUCTOR -> {
                    companion = repository.getBasicGroup(
                        (conversation.type as TdApi.ChatTypeBasicGroup).basicGroupId, conversation
                    ).first().data
                }
                TdApi.ChatTypeSupergroup.CONSTRUCTOR -> {
                    companion = repository.getSupergroup(
                        (conversation.type as TdApi.ChatTypeSupergroup).supergroupId, conversation
                    ).first().data
                }
                TdApi.ChatTypeSecret.CONSTRUCTOR -> {
                    companion = repository.getUser(id).first().data
                }
                else -> {
                    companion = null
                }
            }

            var lastMessage = Message()
            if (conversation.lastMessage != null) {
                lastMessage = Message.tgParse(conversation.lastMessage!!)
            }

            val unreadCount = conversation.unreadCount
            val canWrite = conversation.permissions.canSendMessages

            return Conversation(id, companion, lastMessage, unreadCount, canWrite, Constants.Messenger.TG)
        }
    }
}
