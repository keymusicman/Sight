package com.keymusicman.sample

import com.keymusicman.graph.AppFlowGraph
import com.keymusicman.graph.AppFlowTransition

@AppFlowGraph(name = "Sample App", entrySubgraph = "onboarding")
@AppFlowTransition(fromSubgraph = "onboarding", fromScreen = "Login", toSubgraph = "main", toScreen = "Home", trigger = "login_success")
@AppFlowTransition(fromSubgraph = "main", fromScreen = "Home", toSubgraph = "profile", toScreen = "Profile", trigger = "avatar_tap")
object AppNavigationGraph
