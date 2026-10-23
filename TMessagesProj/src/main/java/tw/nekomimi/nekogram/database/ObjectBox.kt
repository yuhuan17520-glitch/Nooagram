package tw.nekomimi.nekogram.database

import io.objectbox.Box
import io.objectbox.BoxStore
import io.objectbox.BoxStoreBuilder
import org.telegram.messenger.ApplicationLoader
import tw.nekomimi.nekogram.transtale.TranslateDb
import xyz.nextalone.nagram.network.NetworkLogDb
import java.io.File


@JvmOverloads
fun mkDatabase(name: String, delete: Boolean = false): BoxStore {

    // Keep XF SQLite caches ($name.db) untouched during app upgrades.
    val dir = File("${ApplicationLoader.getDataDirFixed()}/databases/$name")
    if (delete) {
        dir.deleteRecursively()
    }

    fun create(): BoxStore {
        val builder: BoxStoreBuilder = MyObjectBox.builder()
        return builder
            .androidContext(ApplicationLoader.applicationContext)
            .directory(dir)
            .noReaderThreadLocals()
            .build()
    }

    // Do not silently erase translations/settings when opening a store fails.
    return create()
}

fun queryTransItemModel(box: Box<TransItem>, code: String, text: String): TransItem? {
    val query = box.query(TransItem_.code.equal(code).and(TransItem_.text.equal(text)))
            .build()
    return query.findFirst()
}

fun clearAll() {
    TranslateDb.clearAll()
    NetworkLogDb.clearAll()
}
