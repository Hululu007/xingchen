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

    public static final Migration MIGRATION_40_41 = new Migration(40, 41) {
        @Override
        public void migrate(@NonNull SupportSQLiteDatabase database) {
            // Keep 主键由单 key 换复合 (key, cid, type)：同名收藏跨配置/跨类型互覆的根治（H14）。
            // SQLite 改主键只能重建表。旧表 key 本就唯一，(key, cid, type) 必然不冲突，
            // INSERT 全量直搬，旧收藏一行不丢、不改值；同名不同配置的旧行在旧表里已被顶掉
            // 的部分无从恢复，保留下来的行原样保留，升级后新写各归各行不再互覆。
            database.execSQL("CREATE TABLE IF NOT EXISTS `Keep_new` (`key` TEXT NOT NULL, `siteName` TEXT, `vodName` TEXT, `vodPic` TEXT, `createTime` INTEGER NOT NULL, `type` INTEGER NOT NULL, `cid` INTEGER NOT NULL, PRIMARY KEY(`key`, `cid`, `type`))");
            database.execSQL("INSERT INTO `Keep_new` (`key`, `siteName`, `vodName`, `vodPic`, `createTime`, `type`, `cid`) SELECT `key`, `siteName`, `vodName`, `vodPic`, `createTime`, `type`, `cid` FROM `Keep`");
            database.execSQL("DROP TABLE IF EXISTS `Keep`");
            database.execSQL("ALTER TABLE `Keep_new` RENAME TO `Keep`");
        }
    };

    public static final Migration MIGRATION_41_42 = new Migration(41, 42) {
        @Override
        public void migrate(@NonNull SupportSQLiteDatabase database) {
            // 音乐身份修复：①收藏/歌单补 localPath——已下载歌（platform=local）的取链只认
            // localPath，旧行没有这列、收藏/进歌单后回读成死条目；②唯一索引从裸 trackId
            // 扩成 (trackId, platform)——trackId 只是源内编号，跨来源同 id 互相顶掉。
            // 旧表 trackId 本就全局唯一，复合键必然不冲突，存量行一行不丢、不改值；
            // 旧行 localPath 留空（多为在线歌，在线取链不看这列）。
            database.execSQL("ALTER TABLE MusicFavorite ADD COLUMN localPath TEXT");
            database.execSQL("ALTER TABLE MusicPlaylistItem ADD COLUMN localPath TEXT");
            database.execSQL("DROP INDEX IF EXISTS `index_MusicFavorite_trackId`");
            database.execSQL("CREATE UNIQUE INDEX IF NOT EXISTS `index_MusicFavorite_trackId_platform` ON `MusicFavorite` (`trackId`, `platform`)");
            database.execSQL("DROP INDEX IF EXISTS `index_MusicPlaylistItem_playlistId_trackId`");
            database.execSQL("CREATE UNIQUE INDEX IF NOT EXISTS `index_MusicPlaylistItem_playlistId_trackId_platform` ON `MusicPlaylistItem` (`playlistId`, `trackId`, `platform`)");
        }
    };
}
