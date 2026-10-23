package com.radolyn.ayugram.database;

import android.content.Context;
import android.database.sqlite.SQLiteDatabase;

import androidx.room.Room;

import com.radolyn.ayugram.database.entities.DeletedDialog;
import com.radolyn.ayugram.database.entities.DeletedMessageFull;
import com.radolyn.ayugram.database.entities.RegexFilter;

import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;

import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.List;

import static org.junit.Assert.*;

/** Synthetic old Nooagram v35 database, not a substitute for a signed device upgrade. */
@RunWith(RobolectricTestRunner.class)
@Config(application = android.app.Application.class, sdk = 28)
public class AyuDatabaseCompatibilityTest {
    @Test public void v29DeletedDialogSurvivesRoomMigrationToCurrentSchema() throws Exception {
        Context context = RuntimeEnvironment.getApplication();
        String name = "nooagram-v29-deleted-dialog.db";
        context.deleteDatabase(name);
        try {
            JSONObject schema;
            try (InputStream input = getClass().getResourceAsStream("/com.radolyn.ayugram.database.AyuDatabase/29.json")) {
                assertNotNull(input);
                schema = new JSONObject(new String(input.readAllBytes(), StandardCharsets.UTF_8)).getJSONObject("database");
            }
            assertEquals(29, schema.getInt("version"));
            try (SQLiteDatabase old = context.openOrCreateDatabase(name, Context.MODE_PRIVATE, null)) {
                JSONArray entities = schema.getJSONArray("entities");
                for (int i = 0; i < entities.length(); i++) {
                    JSONObject entity = entities.getJSONObject(i);
                    old.execSQL(entity.getString("createSql").replace("${TABLE_NAME}", entity.getString("tableName")));
                    JSONArray indices = entity.optJSONArray("indices");
                    for (int j = 0; indices != null && j < indices.length(); j++) {
                        old.execSQL(indices.getJSONObject(j).getString("createSql").replace("${TABLE_NAME}", entity.getString("tableName")));
                    }
                }
                JSONArray setup = schema.getJSONArray("setupQueries");
                for (int i = 0; i < setup.length(); i++) old.execSQL(setup.getString(i));
                old.execSQL("INSERT INTO DeletedDialog(fakeId,userId,dialogId,peerId,folderId,topMessage,lastMessageDate,flags,entityCreateDate) " +
                        "VALUES(17,1000,-2000,-2000,3,44,123456,5,654321)");
                old.setVersion(29);
            }

            AyuDatabase migrated = Room.databaseBuilder(context, AyuDatabase.class, name)
                    .allowMainThreadQueries()
                    .addMigrations(AyuData.Migrations.V29_TO_CURRENT)
                    .build();
            try {
                DeletedDialog dialog = migrated.deletedDialogDao().get(1000, -2000);
                assertNotNull(dialog);
                assertEquals(17, dialog.fakeId);
                assertEquals(-2000, dialog.peerId);
                assertEquals(Integer.valueOf(3), dialog.folderId);
                assertEquals(44, dialog.topMessage);
                assertEquals(123456, dialog.lastMessageDate);
                assertEquals(5, dialog.flags);
                assertEquals(654321, dialog.entityCreateDate);
                assertEquals(1, migrated.deletedDialogDao().getDeletedCount());
            } finally {
                migrated.close();
            }
        } finally {
            context.deleteDatabase(name);
        }
    }

    @Test public void v35ArchiveAndFilterSurviveOpeningOnTheNewRoomRuntime() throws Exception {
        Context context = RuntimeEnvironment.getApplication();
        String name = "nooagram-upgrade-fixture.db";
        context.deleteDatabase(name);
        try {
            JSONObject schema;
            try (InputStream input = getClass().getResourceAsStream("/com.radolyn.ayugram.database.AyuDatabase/35.json")) {
                assertNotNull(input);
                schema = new JSONObject(new String(input.readAllBytes(), StandardCharsets.UTF_8)).getJSONObject("database");
            }
            assertEquals(35, schema.getInt("version"));
            assertEquals("388c57706282c0f638478a2af2440240", schema.getString("identityHash"));
            try (SQLiteDatabase old = context.openOrCreateDatabase(name, Context.MODE_PRIVATE, null)) {
                JSONArray entities = schema.getJSONArray("entities");
                for (int i = 0; i < entities.length(); i++) {
                    JSONObject entity = entities.getJSONObject(i);
                    old.execSQL(entity.getString("createSql").replace("${TABLE_NAME}", entity.getString("tableName")));
                    JSONArray indices = entity.optJSONArray("indices");
                    for (int j = 0; indices != null && j < indices.length(); j++) {
                        old.execSQL(indices.getJSONObject(j).getString("createSql").replace("${TABLE_NAME}", entity.getString("tableName")));
                    }
                }
                JSONArray setup = schema.getJSONArray("setupQueries");
                for (int i = 0; i < setup.length(); i++) old.execSQL(setup.getString(i));
                JSONObject deleted = null;
                for (int i = 0; i < entities.length(); i++) {
                    if (entities.getJSONObject(i).getString("tableName").equals("DeletedMessage")) deleted = entities.getJSONObject(i);
                }
                assertNotNull(deleted);
                android.content.ContentValues values = new android.content.ContentValues();
                JSONArray fields = deleted.getJSONArray("fields");
                for (int i = 0; i < fields.length(); i++) {
                    JSONObject field = fields.getJSONObject(i);
                    if (field.optBoolean("notNull", false)) values.put(field.getString("columnName"), 0);
                }
                values.put("fakeId", 1);
                values.put("userId", 1000L);
                values.put("dialogId", -2000L);
                values.put("messageId", 300);
                values.put("text", "old archive 中文");
                values.put("documentSerialized", new byte[]{1, 2, 3, 4});
                assertTrue(old.insertOrThrow("DeletedMessage", null, values) > 0);
                old.execSQL("INSERT INTO RegexFilter(id,text,dialogId,enabled,caseInsensitive,reversed) VALUES('stable-id','old rule',NULL,1,1,0)");
                old.setVersion(35);
            }
            AyuDatabase migrated = Room.databaseBuilder(context, AyuDatabase.class, name).allowMainThreadQueries().build();
            try {
                // Match production: generated Room DAOs are always behind LockedDao.
                com.radolyn.ayugram.database.dao.DeletedMessageDao archived = LockedDao.wrap(
                        com.radolyn.ayugram.database.dao.DeletedMessageDao.class, migrated::deletedMessageDao);
                assertTrue(java.lang.reflect.Proxy.isProxyClass(archived.getClass()));
                DeletedMessageFull message = archived.getMessage(1000, -2000, 300);
                assertNotNull(message);
                assertEquals("old archive 中文", message.message.text);
                assertArrayEquals(new byte[]{1, 2, 3, 4}, message.message.documentSerialized);
                com.radolyn.ayugram.database.dao.RegexFilterDao filterDao = LockedDao.wrap(
                        com.radolyn.ayugram.database.dao.RegexFilterDao.class, migrated::regexFilterDao);
                List<RegexFilter> filters = filterDao.getShared();
                assertEquals(1, filters.size());
                assertEquals("stable-id", filters.get(0).id);
                assertEquals("old rule", filters.get(0).text);
            } finally {
                migrated.close();
            }
        } finally {
            context.deleteDatabase(name);
        }
    }
}
