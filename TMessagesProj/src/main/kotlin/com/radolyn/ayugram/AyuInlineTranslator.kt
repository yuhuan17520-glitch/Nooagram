package com.radolyn.ayugram

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.telegram.messenger.MessageObject
import org.telegram.messenger.UserConfig
import org.telegram.tgnet.TLRPC
import tw.nekomimi.nekogram.NekoConfig
import tw.nekomimi.nekogram.parts.MessageTransPoll
import tw.nekomimi.nekogram.parts.MessageTransText
import tw.nekomimi.nekogram.transtale.Translator
import tw.nekomimi.nekogram.transtale.code2Locale
import tw.nekomimi.nekogram.transtale.transDb
import java.util.Locale

/** Archived/history bubbles use the same providers and rich text pipeline as Nagram chats. */
class AyuInlineTranslator {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    fun close() = scope.cancel()

    fun toggle(message: MessageObject, requestedTarget: Locale?, onChanged: Runnable, onError: Runnable) {
        if (message.translating) return
        if (message.translatedLocally) {
            message.messageOwner.translatedText = null
            message.messageOwner.translatedPoll = null
            message.translatedLocally = false
            message.updateTranslation(true)
            onChanged.run()
            return
        }
        val target = requestedTarget ?: NekoConfig.translateToLang.String().code2Locale
        val account = message.currentAccount
        val identity = UserConfig.getInstance(account).getClientUserId()
        val loginTime = UserConfig.getInstance(account).loginTime
        if (identity == 0L) return
        val source = message.messageOwner
        message.translating = true
        scope.launch {
            try {
                val result = withContext(Dispatchers.IO) {
                    val db = target.transDb
                    if (message.isPoll) {
                        val skeleton = MessageTransPoll.pollTextOf(message) ?: return@withContext null
                        MessageTransPoll.translate(skeleton, target, emptyList(), db)
                    } else {
                        val text = source.message?.takeIf { it.isNotBlank() } ?: return@withContext null
                        val richText = TLRPC.TL_textWithEntities().also {
                            it.text = text
                            it.entities = source.entities
                        }
                        val separator = MessageTransText.separatorFor(false)
                        MessageTransText.mergeCached(richText, db, separator)
                            ?: MessageTransText.merge(richText,
                                Translator.translate(target, MessageTransText.queryTextOf(richText)), separator)
                    }
                }
                if (result == null || UserConfig.getInstance(account).getClientUserId() != identity
                    || UserConfig.getInstance(account).loginTime != loginTime
                    || message.messageOwner !== source) return@launch
                if (message.isPoll) {
                    source.translatedPoll = result as org.telegram.messenger.TranslateController.PollText
                } else {
                    source.translatedText = result as TLRPC.TL_textWithEntities
                }
                message.translatedLocally = true
                message.updateTranslation(true)
                onChanged.run()
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                org.telegram.messenger.FileLog.e(error)
                if (UserConfig.getInstance(account).getClientUserId() == identity
                    && UserConfig.getInstance(account).loginTime == loginTime) onError.run()
            } finally {
                message.translating = false
            }
        }
    }
}
