package com.keymusicman.sample

import com.keymusicman.sight.SightGraph
import com.keymusicman.sight.SightTransition

@SightGraph(name = "Sample App", entrySubgraph = "onboarding")
@SightTransition(fromSubgraph = "onboarding", fromScreen = "Login", toSubgraph = "main", toScreen = "Home", trigger = "login_success")
@SightTransition(fromSubgraph = "main", fromScreen = "Home", toSubgraph = "profile", toScreen = "Profile", trigger = "avatar_tap")
object AppNavigationGraph
