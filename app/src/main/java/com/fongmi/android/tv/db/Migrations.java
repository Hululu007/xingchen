package com.fongmi.android.tv.db;

import androidx.annotation.NonNull;
import androidx.room.migration.Migration;
import androidx.sqlite.db.SupportSQLiteDatabase;

public class Migrations {

    public static final Migration MIGRATION_30_31 = new Migration(30, 31) {
        @Override
        public void migrate(@NonNull SupportSQLiteDatabase database) {
            database.execSQL("DROP TABLE Track");
            database.execSQL("CREATE TABLE Track (`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, `type` INTEGER NOT NULL, `group` INTEGER NOT NULL, `track` INTEGER NOT NULL, `key` TEXT, `name` TEXT, `selected` INTEGER NOT NULL, `adaptive` INTEGER NOT NULL)");
            database.execSQL("CREATE UNIQUE INDEX IF NOT EXISTS `index_Track_key_type` ON `Track` (`key`, `type`)");
        }
    };

    public static final Migration MIGRATION_31_32 = new Migration(31, 32) {
        @Override
        public void migrate(@NonNull SupportSQLiteDatabase database) {
            database.execSQL("CREATE TABLE History_Backup (`key` TEXT NOT NULL, `vodPic` TEXT, `vodName` TEXT, `vodFlag` TEXT, `vodRemarks` TEXT, `episodeUrl` TEXT, `revSort` INTEGER NOT NULL, `revPlay` INTEGER NOT NULL, `createTime` INTEGER NOT NULL, `opening` INTEGER NOT NULL, `ending` INTEGER NOT NULL, `position` INTEGER NOT NULL, `duration` INTEGER NOT NULL, `speed` REAL NOT NULL, `scale` INTEGER NOT NULL, `cid` INTEGER NOT NULL, PRIMARY KEY(`key`))");
            database.execSQL("INSERT INTO History_Backup SELECT `key`, `vodPic`, `vodName`, `vodFlag`, `vodRemarks`, `episodeUrl`, `revSort`, `revPlay`, `createTime`, `opening`, `ending`, `position`, `duration`, `speed`, `scale`, `cid` FROM History");
            database.execSQL("DROP TABLE History");
            database.execSQL("ALTER TABLE History_Backup RENAME to History");
        }
    };

    public static final Migration MIGRATION_32_33 = new Migration(32, 33) {
        @Override
        public void migrate(@NonNull SupportSQLiteDatabase database) {
            database.execSQL("ALTER TABLE Live ADD COLUMN keep TEXT DEFAULT NULL");
        }
    };

    public static final Migration MIGRATION_33_34 = new Migration(33, 34) {
        @Override
        public void migrate(@NonNull SupportSQLiteDatabase database) {
            database.execSQL("DROP TABLE Track");
            database.execSQL("CREATE TABLE Track (`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, `type` INTEGER NOT NULL, `group` INTEGER NOT NULL, `track` INTEGER NOT NULL, `key` TEXT, `name` TEXT, `selected` INTEGER NOT NULL)");
            database.execSQL("CREATE UNIQUE INDEX IF NOT EXISTS `index_Track_key_type` ON `Track` (`key`, `type`)");
        }
    };

    public static final Migration MIGRATION_34_35 = new Migration(34, 35) {
        @Override
        public void migrate(@NonNull SupportSQLiteDatabase database) {
            database.execSQL("DROP TABLE Track");
            database.execSQL("CREATE TABLE Track (`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, `type` INTEGER NOT NULL, `key` TEXT, `name` TEXT, `format` TEXT, `selected` INTEGER NOT NULL)");
            database.execSQL("CREATE UNIQUE INDEX IF NOT EXISTS `index_Track_key_type` ON `Track` (`key`, `type`)");
        }
    };

    public static final Migration MIGRATION_35_36 = new Migration(35, 36) {
        @Override
        public void migrate(@NonNull SupportSQLiteDatabase database) {
            database.execSQL("ALTER TABLE History ADD COLUMN wallPic TEXT DEFAULT NULL");
        }
    };

    public static final Migration MIGRATION_36_37 = new Migration(36, 37) {
        @Override
        public void migrate(@NonNull SupportSQLiteDatabase database) {
            database.execSQL("CREATE TABLE IF NOT EXISTS PlaybackDeleteTombstone (`id` TEXT NOT NULL, `configKey` TEXT NOT NULL, `scope` TEXT NOT NULL, `historyKey` TEXT NOT NULL, `siteKey` TEXT NOT NULL, `vodId` TEXT NOT NULL, `deletedAt` INTEGER NOT NULL, PRIMARY KEY(`id`))");
            database.execSQL("CREATE INDEX IF NOT EXISTS `index_PlaybackDeleteTombstone_deletedAt` ON `PlaybackDeleteTombstone` (`deletedAt`)");
        }
    };

    public static final Migration MIGRATION_37_38 = new Migration(37, 38) {
        @Override
        public void migrate(@NonNull SupportSQLiteDatabase database) {
            database.execSQL("CREATE TABLE IF NOT EXISTS MusicFavorite (`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, `trackId` TEXT NOT NULL, `title` TEXT, `artist` TEXT, `album` TEXT, `duration` INTEGER NOT NULL, `cover` TEXT, `createTime` INTEGER NOT NULL)");
            database.execSQL("CREATE UNIQUE INDEX IF NOT EXISTS `index_MusicFavorite_trackId` ON `MusicFavorite` (`trackId`)");
            database.execSQL("CREATE TABLE IF NOT EXISTS MusicPlaylist (`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, `name` TEXT, `createTime` INTEGER NOT NULL, `updateTime` INTEGER NOT NULL)");
            database.execSQL("CREATE TABLE IF NOT EXISTS MusicPlaylistItem (`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, `playlistId` INTEGER NOT NULL, `trackId` TEXT, `title` TEXT, `artist` TEXT, `album` TEXT, `duration` INTEGER NOT NULL, `cover` TEXT, `position` INTEGER NOT NULL, `createTime` INTEGER NOT NULL)");
            database.execSQL("CREATE INDEX IF NOT EXISTS `index_MusicPlaylistItem_playlistId` ON `MusicPlaylistItem` (`playlistId`)");
            database.execSQL("CREATE UNIQUE INDEX IF NOT EXISTS `index_MusicPlaylistItem_playlistId_trackId` ON `MusicPlaylistItem` (`playlistId`, `trackId`)");
        }
    };

    public static final Migration MIGRATION_38_39 = new Migration(38, 39) {
        @Override
        public void migrate(@NonNull SupportSQLiteDatabase database) {
            database.execSQL("CREATE TABLE IF NOT EXISTS MusicDownload (`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, `trackId` TEXT NOT NULL, `title` TEXT, `artist` TEXT, `album` TEXT, `duration` INTEGER NOT NULL, `cover` TEXT, `filePath` TEXT, `createTime` INTEGER NOT NULL)");
            database.execSQL("CREATE UNIQUE INDEX IF NOT EXISTS `index_MusicDownload_trackId` ON `MusicDownload` (`trackId`)");
        }
    };

    public static final Migration MIGRATION_39_40 = new Migration(39, 40) {
        @Override
        public void migrate(@NonNull SupportSQLiteDatabase database) {
            // 歌单/收藏存源信息（platform＋rawJson），点播才不丢源；旧行留空=未知来源
            database.execSQL("ALTER TABLE MusicFavorite ADD COLUMN platform TEXT");
            database.execSQL("ALTER TABLE MusicFavorite ADD COLUMN rawJson TEXT");
            database.execSQL("ALTER TABLE MusicPlaylistItem ADD COLUMN platform TEXT");
            database.execSQL("ALTER TABLE MusicPlaylistItem ADD COLUMN rawJson TEXT");
        }
    };
}
