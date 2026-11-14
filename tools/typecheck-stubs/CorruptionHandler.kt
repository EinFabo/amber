package androidx.datastore.core.handlers

class ReplaceFileCorruptionHandler<T>(private val produceNewData: (Exception) -> T)
