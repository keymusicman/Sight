package io.github.keymusicman.sample

import io.github.keymusicman.sight.SightGraph
import io.github.keymusicman.sight.SightTransition

@SightGraph(name = "Sample App", entrySubgraph = "onboarding")
@SightTransition(fromSubgraph = "onboarding", fromScreen = "Login", toSubgraph = "main", toScreen = "Home", trigger = "login_success")
@SightTransition(fromSubgraph = "main", fromScreen = "Home", toSubgraph = "profile", toScreen = "Profile", trigger = "avatar_tap")
object AppNavigationGraph
