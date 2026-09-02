package team.themoment.axia.app

import androidx.compose.runtime.Composable
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshots.SnapshotStateList
import androidx.compose.runtime.toMutableStateList
import team.themoment.axia.navigation.AxiaAccessFlags
import team.themoment.axia.navigation.AxiaDestination
import team.themoment.axia.navigation.destinationsFor

@Stable
class AxiaAppState internal constructor(
    initialDestination: AxiaDestination,
    initialAccessFlags: AxiaAccessFlags,
) {
    private val mutableDestinations: SnapshotStateList<AxiaDestination> =
        destinationsFor(initialAccessFlags).toMutableStateList()

    var currentDestination by
        mutableStateOf(
            initialDestination.takeIf { it in mutableDestinations } ?: AxiaDestination.Dashboard,
        )
        private set

    var accessFlags by mutableStateOf(initialAccessFlags)
        private set

    val destinations: List<AxiaDestination>
        get() = mutableDestinations

    fun navigate(destination: AxiaDestination) {
        if (destination in mutableDestinations) {
            currentDestination = destination
        }
    }

    fun updateAccessFlags(newAccessFlags: AxiaAccessFlags) {
        if (newAccessFlags == accessFlags) return

        accessFlags = newAccessFlags
        mutableDestinations.clear()
        mutableDestinations.addAll(destinationsFor(newAccessFlags))

        if (currentDestination !in mutableDestinations) {
            currentDestination = AxiaDestination.Dashboard
        }
    }
}

@Composable
fun rememberAxiaAppState(
    accessFlags: AxiaAccessFlags = AxiaAccessFlags(),
    initialDestination: AxiaDestination = AxiaDestination.Dashboard,
): AxiaAppState {
    val state =
        remember(initialDestination) {
            AxiaAppState(
                initialDestination = initialDestination,
                initialAccessFlags = accessFlags,
            )
        }
    SideEffect {
        state.updateAccessFlags(accessFlags)
    }
    return state
}
