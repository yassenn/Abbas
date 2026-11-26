package ai.alal.app.data

/**
 * v5 → v6 statements: independent summary records. Strictly additive (ALTER ... ADD COLUMN
 * plus one new table), so every existing digest keeps its id, text and timestamps. Kept as
 * plain data so a unit test can assert it never drops or rewrites existing rows.
 */
object SummaryMigration {
    val STATEMENTS_5_6: List<String> = listOf(
        "ALTER TABLE `digests` ADD COLUMN `title` TEXT",
        "ALTER TABLE `digests` ADD COLUMN `updatedAt` INTEGER NOT NULL DEFAULT 0",
        "ALTER TABLE `digests` ADD COLUMN `pinned` INTEGER NOT NULL DEFAULT 0",
        "ALTER TABLE `digests` ADD COLUMN `sourceRef` TEXT",
        // Existing digests came from phone activity and were last "updated" when created.
        "UPDATE `digests` SET `updatedAt` = `createdAt`, `sourceRef` = '${SummaryOrigin.ITEMS}'",
        "CREATE TABLE IF NOT EXISTS `summary_links` (`childId` INTEGER NOT NULL, `parentId` INTEGER NOT NULL, " +
            "PRIMARY KEY(`childId`, `parentId`), " +
            "FOREIGN KEY(`childId`) REFERENCES `digests`(`id`) ON UPDATE NO ACTION ON DELETE CASCADE, " +
            "FOREIGN KEY(`parentId`) REFERENCES `digests`(`id`) ON UPDATE NO ACTION ON DELETE CASCADE)",
        "CREATE INDEX IF NOT EXISTS `index_summary_links_parentId` ON `summary_links` (`parentId`)"
    )
}
