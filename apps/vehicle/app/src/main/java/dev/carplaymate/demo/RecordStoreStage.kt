package dev.carplaymate.demo

/** In-process fault injection only. No production logging or callbacks are installed. */
internal enum class RecordStoreStage {
    LEGACY_PRESERVED,
    IMPORT_ROWS_WRITTEN,
    IMPORT_COMMITTED,
    FENCE_WRITTEN,
    STORE_ACTIVATED,
    BEFORE_COMMIT,
    AFTER_COMMIT,
    BEFORE_DELETE,
    DATABASE_DELETED,
    ALL_FILES_DELETED,
}

/** Aggregate timings only when a test installs an observer; no record data is exposed. */
internal enum class RecordStoreWork {
    ROW_QUERY, ROW_DECRYPT, ROW_PARSE, ROW_SERIALIZE, ROW_ENCRYPT, ROW_WRITE, COMMIT,
}
