package ru.papajon.meshcentralmobile.mesh

/** Consume the iterator: Collection.toList may read size=1, then call next after the entry vanished. */
internal fun <T> concurrentSnapshot(values: Set<T>): List<T> = values.iterator().asSequence().toList()
