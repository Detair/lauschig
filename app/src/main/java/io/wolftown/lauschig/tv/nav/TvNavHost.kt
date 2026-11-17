package io.wolftown.lauschig.tv.nav

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.navigation.compose.composable
import io.wolftown.lauschig.tv.TvDimens
import io.wolftown.lauschig.tv.home.TvHomeScreen
import io.wolftown.lauschig.tv.setup.TvSetupRoute
import io.wolftown.lauschig.ui.nav.AccountNavHost
import io.wolftown.lauschig.ui.nav.AccountNavigation
import io.wolftown.lauschig.ui.nav.Setup
import io.wolftown.lauschig.ui.nav.SetupMode
import io.wolftown.lauschig.ui.nav.finishSetup
import io.wolftown.lauschig.ui.profiles.ProfilePickerRoute

/**
 * The TV's pages of one account. Like the phone's, they live in [AccountNavHost], so a switch of the account ends them all.
 * Back pops a page; on the first page it leaves the app.
 */
@Composable
fun TvNavHost(pages: AccountNavigation, startDestination: Any, modifier: Modifier = Modifier) {
    val navController = pages.controller
    AccountNavHost(pages, startDestination, modifier) {
        composable<TvHome> { TvHomeScreen() }
        // The phone's route class: SetupViewModel reads its mode from it.
        composable<Setup> { TvSetupRoute(onDone = { navController.finishSetup(TvHome) }) }
        // Provisional: the phone's picker, so that a TV with saved profiles and nobody active is not stuck on an empty page.
        composable<TvProfiles> {
            Box(Modifier.padding(horizontal = TvDimens.overscanHorizontal, vertical = TvDimens.overscanVertical)) {
                ProfilePickerRoute(
                    onAdd = { navController.navigate(Setup(SetupMode.ADD_PROFILE)) { launchSingleTop = true } },
                    onNoneLeft = { navController.navigate(Setup()) { popUpTo<TvProfiles> { inclusive = true } } },
                    onSignIn = { navController.navigate(Setup.unlock(it)) { launchSingleTop = true } },
                )
            }
        }
    }
}
