# Graph definition options

If there are no `@AppFlowGraph` is defined or the `@AppFlowGraph` is not named, then all `@AppFlowScreen` will be considered part of the same default graph

## Default graph, no connections

It should be possible to mark individual previews with @AppFlowScreen only and this should work. No @AppFlowGraph should be required

```kotlin
@AppFlowScreen
@Composable
@Preview
fun Main() {}

@AppFlowScreen
@Composable
@Preview
fun Login() {}
```

This setup should work the same in multi-module project

## Default graph, connections via screen IDs

```kotlin
@AppFlowScreen
@AppFlowTransition(toScreen = "profile", trigger = "profile_tap", id = "main")
@Composable
@Preview
fun MainScreen() {}

@AppFlowScreen(subgraph = "profile", id = "profile")
@Composable
@Preview
fun Profile() {}
```

The `MainScreen` should be considered the root of the graph in this case

## Default graph, Subgraphs with explicit entry points

```kotlin
@AppFlowScreen(subgraph = "main", isRoot = true)
@AppFlowTransition(toSubgraph = "profile", trigger = "profile_tap")
@Composable
@Preview
fun MainScreen() {}

@AppFlowScreen(subgraph = "profile", isRoot = true, id = "profile")
@Composable
@Preview
fun Profile() {}
```

The `MainScreen` should be considered the root of the graph in this case. Also, it should be connected to the `Profile` screen

## Default graph, Connections between non-root screens

```kotlin
@AppFlowScreen(subgraph = "main", isRoot = true)
@AppFlowTransition(toScreen = "profile_2", trigger = "profile_tap")
@Composable
@Preview
fun MainScreen() {}

@AppFlowScreen(subgraph = "profile", isRoot = true, id = "profile")
@Composable
@Preview
fun Profile() {}

@AppFlowScreen(subgraph = "profile", id = "profile_2")
@Composable
@Preview
fun Profile2() {}
```

The `MainScreen` should be considered the root of the graph in this case. Also, it should be connected to the `Profile2` screen, but not to the `Profile` screen (since the transition points to `profile_2`)

## Single default graph, screen connections defined on graph

```kotlin
@AppFlowGraph
@AppFlowTransition(fromScreen = "main", toScreen = "transaction_details")
object Graph

@AppFlowScreen(subgraph = "main", isRoot = true)
@AppFlowTransition(toSubgraph = "profile", trigger = "profile_tap")
@Composable
@Preview
fun Main() {}

@AppFlowScreen(subgraph = "profile", isRoot = true, id = "profile")
@Composable
@Preview
fun Profile() {}

@AppFlowScreen(subgraph = "main", id = "transaction_details")
@Composable
@Preview
fun TransactionDetails() {}
```

The object `Graph` is unnamed so should be considered the default graph

## Graph entry point

Graph entry subgraph can be defined on `AppFlowGraph`. In this case the subgraph root node should be considered the entry point of the graph, even if there are other root nodes in the graph:

```kotlin
@AppFlowGraph(entrySubgraph = "main")
@AppFlowTransition(fromScreen = "main", toScreen = "transaction_details")
object Graph

@AppFlowScreen(subgraph = "main", isRoot = true, id = "main")
@Composable
@Preview
fun Main() {}

@AppFlowScreen(subgraph = "main", id = "transaction_details")
@Composable
@Preview
fun TransactionDetails() {}
```

In this case the Main will be the entry node of the whole graph

## When entry point or multiple graphs are defined, every graph should contain only connected subgraphs and nodes

```kotlin
@AppFlowGraph(entrySubgraph = "main_1")
object Graph

@AppFlowScreen(subgraph = "main_1", isRoot = true, id = "main")
@Composable
@Preview
fun Main1() {}

@AppFlowScreen(subgraph = "main_2", isRoot = true, id = "main")
@Composable
@Preview
fun Main2() {}
```

AppFlowGraph should have the `dropUnconnected` flag which is true by default. When this flag is true, only the subgraphs and nodes connected to the entry point will be included in the graph. In this case, since there are two separate entry points (main_1 and main_2) and no connections between them, only the subgraph with main_1 will be included in the graph and drawn. The subgraph with main_2 will be dropped since it is not connected to the entry point defined in the AppFlowGraph.

## Multiple graphs

```kotlin
@AppFlowGraph(name = "auth_graph")
object AuthGraph

@AppFlowGraph(name = "main")
@AppFlowTransition(fromScreen = "main", toSubgraph = "auth", trigger = "registration_complete")
object MainGraph

@AppFlowScreen(subgraph = "auth", isRoot = true, id = "login")
@AppFlowTransition(toScreen = "registration", trigger = "register_tap")
@Composable
@Preview
fun Login() {}

@AppFlowScreen(subgraph = "auth", id = "registration")
@Composable
@Preview
fun Registration() {}

@AppFlowScreen(subgraph = "main", isRoot = true, id = "main")
@Composable
@Preview
fun Main() {}
```

There will be two graphs. The dropdown "Graph" should appear with all the available graphs (AuthGraph, MainGraph) and allow switching between them. In the plugin panel there should be "+" button which allows to add one more tab. Every tab contains the same: dropdown "Graph" with all the available graphs and the graph visualization

## Multi-modular default graph example

### Module 1

```kotlin
@AppFlowGraph
@AppFlowTransition(fromScreen = "login", toSubgraph = "registration", trigger = "register_tap")
@AppFlowTransition(fromScreen = "account_details", toSubgraph = "main", trigger = "registration_complete")
@AppFlowTransition(fromScreen = "main", toScreen = "transaction_details")
object Graph

@AppFlowScreen(subgraph = "main", isRoot = true)
@AppFlowTransition(toSubgraph = "profile", trigger = "profile_tap")
@AppFlowTransition(toScreen = "transaction_details", trigger = "transaction_tap")
@Composable
@Preview
fun Main() {}

@AppFlowScreen(subgraph = "profile", isRoot = true, id = "profile")
@Composable
@Preview
fun Profile() {}

@AppFlowScreen(subgraph = "main", id = "transaction_details")
@Composable
@Preview
fun TransactionDetails() {}
```

### Module 2

```kotlin
@AppFlowScreen(subgraph = "onboarding", isRoot = true, id = "onboarding")
@AppFlowTransition(toScreen = "login", trigger = "continue")
@Composable
@Preview
fun Onboarding() {}

@AppFlowScreen(subgraph = "onboarding", id = "login")
@Composable
@Preview
fun Login() {}
```

### Module 3

```kotlin
@AppFlowScreen(subgraph = "registration", isRoot = true)
@AppFlowTransition(toScreen = "account_details", trigger = "continue")
@Composable
@Preview
fun Email() {}

@AppFlowScreen(subgraph = "registration", id = "account_details")
@Composable
@Preview
fun AccountDetails() {}
```

## Multi-modular multiple graphs example

Each subgraph is rooted in exactly one module. Other modules — including the ones that only declare `@AppFlowGraph` objects — reference subgraphs by key; the consumer merges every module's fragment into one global pool before building each graph. Re-rooting the same subgraph (or re-declaring the same `subgraph:id`) in a second module is a hard error.

### Module 1 (defines Graph 1)

```kotlin
@AppFlowGraph(name = "graph_1", entrySubgraph = "onboarding")
@AppFlowTransition(fromScreen = "login", toSubgraph = "registration", trigger = "register_tap")
@AppFlowTransition(fromScreen = "account_details", toSubgraph = "main", trigger = "registration_complete")
object Graph

@AppFlowScreen(subgraph = "main", isRoot = true)
@AppFlowTransition(toSubgraph = "profile", trigger = "profile_tap")
@AppFlowTransition(toScreen = "transaction_details", trigger = "transaction_tap")
@Composable
@Preview
fun Main() {}

@AppFlowScreen(subgraph = "profile", isRoot = true, id = "profile")
@Composable
@Preview
fun Profile() {}

@AppFlowScreen(subgraph = "main", id = "transaction_details")
@Composable
@Preview
fun TransactionDetails() {}
```

### Module 2

```kotlin
@AppFlowScreen(subgraph = "onboarding", isRoot = true, id = "onboarding")
@AppFlowScreen(subgraph = "onboarding_dark", isRoot = true, id = "onboarding")
@AppFlowTransition(fromSubgraph = "onboarding", toScreen = "login", trigger = "continue")
@Composable
@Preview
fun Onboarding() {}

@AppFlowScreen(subgraph = "onboarding", id = "login")
@Composable
@Preview
fun Login() {}
```

Here are two subgraphs:
- `onboarding` with root node `onboarding`" connected to `login`
- `onboarding_dark` with root node `onboarding_dark`, not connected

### Module 3

```kotlin
@AppFlowScreen(subgraph = "registration", isRoot = true)
@AppFlowTransition(toScreen = "account_details", trigger = "continue")
@Composable
@Preview
fun Email() {}

@AppFlowScreen(subgraph = "registration", id = "account_details")
@Composable
@Preview
fun AccountDetails() {}
```

### Module 4 (defines graph_2)

```kotlin
@AppFlowGraph(entrySubgraph = "onboarding_dark", name = "graph_2")
@AppFlowTransition(fromScreen = "onboarding", toSubgraph = "main")
object Graph
```

This module defines only the graph object — no screens. `main` is rooted in Module 1 and merged globally, so it is **not** re-declared here. `graph_2`'s entry subgraph `onboarding_dark` is isolated, so with the default `dropUnconnected = true` only the `onboarding_dark` node is included.

### Module 5 (defines graph_3)

```kotlin
@AppFlowGraph(entrySubgraph = "onboarding", dropUnconnected = false, name = "graph_3")
object Graph
```

This module defines only the graph object — no screens. `main` is rooted in Module 1; it is **not** re-declared here. Because `dropUnconnected = false`, every globally-merged subgraph and node is included regardless of connectivity.

In this example, there are three graphs:
- `graph_1` with entry point `onboarding`, which includes the `onboarding` → `login` flow and the `main` → `profile` + `transaction_details` flow. The `onboarding_dark` subgraph is not included in this graph since it is not connected to the entry point.
- `graph_2` with entry point `onboarding_dark`, which includes only the `onboarding_dark` node since it is not connected to any other nodes. The `onboarding` → `login` flow and the `main` → `profile` + `transaction_details` flow are not included in this graph since they are not connected to the entry point.
- `graph_3` with entry point `onboarding`, which includes all nodes and subgraphs since `dropUnconnected` is set to false. This graph includes the `onboarding` → `login` flow, the `main` → `profile` + `transaction_details` flow, and the unconnected `onboarding_dark` subgraph.