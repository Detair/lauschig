package io.wolftown.lauschig.links

import androidx.lifecycle.ViewModel
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject

/** Resolves incoming web links for `AppRoot` (navigation or a message). */
@HiltViewModel
class LinkViewModel @Inject constructor(private val opener: LinkOpener) : ViewModel() {
    suspend fun open(url: String): LinkTarget = opener.open(url)
}
