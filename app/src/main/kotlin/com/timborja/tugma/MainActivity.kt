package com.timborja.tugma

import android.net.Uri
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.BugReport
import androidx.compose.material.icons.outlined.History
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material.icons.outlined.Speaker
import androidx.compose.material3.Icon
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.navigation.NavGraph.Companion.findStartDestination
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import com.timborja.tugma.core.Brand
import com.timborja.tugma.ui.AboutScreen
import com.timborja.tugma.ui.DebugScreen
import com.timborja.tugma.ui.HomeTheaterScreen
import com.timborja.tugma.ui.SnapshotDetailScreen
import com.timborja.tugma.ui.SnapshotsScreen
import com.timborja.tugma.ui.SpeakerDetailScreen
import com.timborja.tugma.ui.SpeakersScreen
import com.timborja.tugma.ui.StereoPairScreen
import com.timborja.tugma.ui.TugmaTheme

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        setContent { TugmaTheme { TugmaNav() } }
    }
}

private data class Tab(val route: String, val label: String, val icon: ImageVector)

private val tabs = listOf(
    Tab("speakers", "Speakers", Icons.Outlined.Speaker),
    Tab("snapshots", "Snapshots", Icons.Outlined.History),
    Tab("debug", "Debug", Icons.Outlined.BugReport),
    Tab("about", "About", Icons.Outlined.Info),
)

@Composable
private fun TugmaNav() {
    val nav = rememberNavController()
    val entry by nav.currentBackStackEntryAsState()
    val route = entry?.destination?.route
    val showTabs = tabs.any { it.route == route }

    Scaffold(
        bottomBar = {
            if (showTabs) {
                NavigationBar {
                    tabs.forEach { tab ->
                        NavigationBarItem(
                            selected = route == tab.route,
                            onClick = {
                                nav.navigate(tab.route) {
                                    popUpTo(nav.graph.findStartDestination().id) { saveState = true }
                                    launchSingleTop = true
                                    restoreState = true
                                }
                            },
                            icon = { Icon(tab.icon, contentDescription = null) },
                            label = { Text(tab.label) },
                        )
                    }
                }
            }
        },
    ) { padding ->
        NavHost(nav, startDestination = "speakers", modifier = Modifier.padding(bottom = padding.calculateBottomPadding())) {
            composable("speakers") {
                SpeakersScreen(
                    onPair = { left -> nav.navigate("pair" + (left?.let { "?left=$it" } ?: "")) },
                    onHomeTheater = { bar -> nav.navigate("ht" + (bar?.let { "?bar=$it" } ?: "")) },
                    onOpen = { brand, id -> nav.navigate("speaker/${brand.name}/${Uri.encode(id)}") },
                )
            }
            composable("speaker/{brand}/{id}") {
                val brand = runCatching { Brand.valueOf(it.arguments?.getString("brand").orEmpty()) }.getOrDefault(Brand.SONOS)
                SpeakerDetailScreen(
                    brand = brand,
                    id = it.arguments?.getString("id").orEmpty(),
                    onBack = { nav.popBackStack() },
                    onPair = { left -> nav.navigate("pair?left=$left") },
                    onHomeTheater = { bar -> nav.navigate("ht?bar=$bar") },
                )
            }
            composable(
                "pair?left={left}",
                arguments = listOf(navArgument("left") { type = NavType.StringType; nullable = true; defaultValue = null }),
            ) { StereoPairScreen(it.arguments?.getString("left"), onBack = { nav.popBackStack() }) }
            composable(
                "ht?bar={bar}",
                arguments = listOf(navArgument("bar") { type = NavType.StringType; nullable = true; defaultValue = null }),
            ) { HomeTheaterScreen(it.arguments?.getString("bar"), onBack = { nav.popBackStack() }) }
            composable("snapshots") { SnapshotsScreen(onOpen = { id -> nav.navigate("snapshot/$id") }) }
            composable("snapshot/{id}") { SnapshotDetailScreen(it.arguments?.getString("id").orEmpty(), onBack = { nav.popBackStack() }) }
            composable("debug") { DebugScreen() }
            composable("about") { AboutScreen() }
        }
    }
}
