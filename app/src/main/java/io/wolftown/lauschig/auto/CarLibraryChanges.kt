package io.wolftown.lauschig.auto

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.map

/**
 * When browsers (Android Auto) must reload what they show: whenever one of the [parts] of the signed-in account changes,
 * and at every change of the account itself, also when the new account's parts look just like the old one's. After
 * [debounceMs] without a further change; a reload throws the car back to the top of a grid.
 */
@OptIn(ExperimentalCoroutinesApi::class, FlowPreview::class)
fun carLibraryChanges(account: Flow<String?>, parts: (serverId: String?) -> List<Flow<Any>>, debounceMs: Long): Flow<Unit> =
    account.distinctUntilChanged().flatMapLatest { sid ->
        combine(parts(sid)) { it.toList() }.distinctUntilChanged().debounce(debounceMs).map { }
    }
