# Graph definition options

If there are no `@SightGraph` is defined or the `@SightGraph` is not named, then all `@SightScreen` will be considered part of the same default graph

## Default graph, no connections

It should be possible to mark individual previews with @SightScreen only and this should work. No @SightGraph should be required

```kotlin
@SightScreen
@Composable
@Preview
fun Main() {}

@SightScreen
@Composable
@Preview
fun Login() {}
```

This setup should work the same in multi-module project

## Default graph, connections via screen IDs

```kotlin
@SightScreen
@SightTransition(toScreen = "profile", trigger = "profile_tap", id = "main")
@Composable
@Preview
fun MainScreen() {}

@SightScreen(subgraph = "profile", id = "profile")
@Composable
@Preview
fun Profile() {}
```

The `MainScreen` should be considered the root of the graph in this case

## Default graph, connection to a screen by id when unambiguous

A function-level `@SightTransition(toScreen = ...)` without a `toSubgraph` is resolved by screen id:

```kotlin
@SightTransition(toScreen = "TransactionDetails")
@SightScreen(subgraph = "main", id = "MainScreen", isRoot = true)
@Composable
@Preview
fun MainScreen() {}

@SightScreen(subgraph = "history", id = "TransactionDetails")
@Composable
@Preview
fun TransactionDetails() {}
```

`MainScreen` connects to `history:TransactionDetails`. Resolution rules for a bare `toScreen` (scoped to the source screen's subgraph):

- **Exactly one screen has that id** → connect to it, whatever subgraph it lives in.
- **Several screens share the id and one is in the source's subgraph** → emit a **warning** and connect to the same-subgraph screen.
- **Several screens share the id and none is in the source's subgraph** → drop the connection with an **error** (the reference is ambiguous).

## Default graph, connection to a screen in the same subgraph

A function-level `@SightTransition(toScreen = ...)` may target another screen in the **same** subgraph as the source:

```kotlin
@SightTransition(toScreen = "ReferralProgramDetailsBottomSheet")
@SightScreen(subgraph = "main", id = "MainScreen", isRoot = true)
@Composable
@Preview
fun MainScreen() {}

@SightScreen(subgraph = "main", id = "ReferralProgramDetailsBottomSheet")
@Composable
@Preview
fun ReferralProgramDetails() {}
```

`MainScreen` should be connected to `ReferralProgramDetailsBottomSheet`. A bare `toScreen` (no `toSubgraph`) resolves against the **source screen's own subgraph first**, then falls back to a global lookup by id. So if the same `id` also exists in another subgraph, the same-subgraph target wins rather than the connection being dropped as ambiguous.

## Default graph, Subgraphs with explicit entry points

```kotlin
@SightScreen(subgraph = "main", isRoot = true)
@SightTransition(toSubgraph = "profile", trigger = "profile_tap")
@Composable
@Preview
fun MainScreen() {}

@SightScreen(subgraph = "profile", isRoot = true, id = "profile")
@Composable
@Preview
fun Profile() {}
```

The `MainScreen` should be considered the root of the graph in this case. Also, it should be connected to the `Profile` screen

## Default graph, Connections between non-root screens

```kotlin
@SightScreen(subgraph = "main", isRoot = true)
@SightTransition(toScreen = "profile_2", trigger = "profile_tap")
@Composable
@Preview
fun MainScreen() {}

@SightScreen(subgraph = "profile", isRoot = true, id = "profile")
@Composable
@Preview
fun Profile() {}

@SightScreen(subgraph = "profile", id = "profile_2")
@Composable
@Preview
fun Profile2() {}
```

The `MainScreen` should be considered the root of the graph in this case. Also, it should be connected to the `Profile2` screen, but not to the `Profile` screen (since the transition points to `profile_2`)

## Single default graph, screen connections defined on graph

```kotlin
@SightGraph
@SightTransition(fromScreen = "main", toScreen = "transaction_details")
object Graph

@SightScreen(subgraph = "main", isRoot = true)
@SightTransition(toSubgraph = "profile", trigger = "profile_tap")
@Composable
@Preview
fun Main() {}

@SightScreen(subgraph = "profile", isRoot = true, id = "profile")
@Composable
@Preview
fun Profile() {}

@SightScreen(subgraph = "main", id = "transaction_details")
@Composable
@Preview
fun TransactionDetails() {}
```

The object `Graph` is unnamed so should be considered the default graph

## Graph entry point

Graph entry subgraph can be defined on `SightGraph`. In this case the subgraph root node should be considered the entry point of the graph, even if there are other root nodes in the graph:

```kotlin
@SightGraph(entrySubgraph = "main")
@SightTransition(fromScreen = "main", toScreen = "transaction_details")
object Graph

@SightScreen(subgraph = "main", isRoot = true, id = "main")
@Composable
@Preview
fun Main() {}

@SightScreen(subgraph = "main", id = "transaction_details")
@Composable
@Preview
fun TransactionDetails() {}
```

In this case the Main will be the entry node of the whole graph

## When entry point or multiple graphs are defined, every graph should contain only connected subgraphs and nodes

```kotlin
@SightGraph(entrySubgraph = "main_1")
object Graph

@SightScreen(subgraph = "main_1", isRoot = true, id = "main")
@Composable
@Preview
fun Main1() {}

@SightScreen(subgraph = "main_2", isRoot = true, id = "main")
@Composable
@Preview
fun Main2() {}
```

SightGraph should have the `dropUnconnected` flag which is true by default. When this flag is true, only the subgraphs and nodes connected to the entry point will be included in the graph. In this case, since there are two separate entry points (main_1 and main_2) and no connections between them, only the subgraph with main_1 will be included in the graph and drawn. The subgraph with main_2 will be dropped since it is not connected to the entry point defined in the SightGraph.

## Multiple graphs

```kotlin
@SightGraph(name = "auth_graph")
object AuthGraph

@SightGraph(name = "main")
@SightTransition(fromScreen = "main", toSubgraph = "auth", trigger = "registration_complete")
object MainGraph

@SightScreen(subgraph = "auth", isRoot = true, id = "login")
@SightTransition(toScreen = "registration", trigger = "register_tap")
@Composable
@Preview
fun Login() {}

@SightScreen(subgraph = "auth", id = "registration")
@Composable
@Preview
fun Registration() {}

@SightScreen(subgraph = "main", isRoot = true, id = "main")
@Composable
@Preview
fun Main() {}
```

There will be two graphs. The dropdown "Graph" should appear with all the available graphs (AuthGraph, MainGraph) and allow switching between them. In the plugin panel there should be "+" button which allows to add one more tab. Every tab contains the same: dropdown "Graph" with all the available graphs and the graph visualization

## Multi-modular default graph example

### Module 1

```kotlin
@SightGraph
@SightTransition(fromScreen = "login", toSubgraph = "registration", trigger = "register_tap")
@SightTransition(fromScreen = "account_details", toSubgraph = "main", trigger = "registration_complete")
@SightTransition(fromScreen = "main", toScreen = "transaction_details")
object Graph

@SightScreen(subgraph = "main", isRoot = true)
@SightTransition(toSubgraph = "profile", trigger = "profile_tap")
@SightTransition(toScreen = "transaction_details", trigger = "transaction_tap")
@Composable
@Preview
fun Main() {}

@SightScreen(subgraph = "profile", isRoot = true, id = "profile")
@Composable
@Preview
fun Profile() {}

@SightScreen(subgraph = "main", id = "transaction_details")
@Composable
@Preview
fun TransactionDetails() {}
```

### Module 2

```kotlin
@SightScreen(subgraph = "onboarding", isRoot = true, id = "onboarding")
@SightTransition(toScreen = "login", trigger = "continue")
@Composable
@Preview
fun Onboarding() {}

@SightScreen(subgraph = "onboarding", id = "login")
@Composable
@Preview
fun Login() {}
```

### Module 3

```kotlin
@SightScreen(subgraph = "registration", isRoot = true)
@SightTransition(toScreen = "account_details", trigger = "continue")
@Composable
@Preview
fun Email() {}

@SightScreen(subgraph = "registration", id = "account_details")
@Composable
@Preview
fun AccountDetails() {}
```

## Multi-modular multiple graphs example

Each subgraph is rooted in exactly one module. Other modules — including the ones that only declare `@SightGraph` objects — reference subgraphs by key; the consumer merges every module's fragment into one global pool before building each graph. Re-rooting the same subgraph (or re-declaring the same `subgraph:id`) in a second module is a hard error.

### Module 1 (defines Graph 1)

```kotlin
@SightGraph(name = "graph_1", entrySubgraph = "onboarding")
@SightTransition(fromScreen = "login", toSubgraph = "registration", trigger = "register_tap")
@SightTransition(fromScreen = "account_details", toSubgraph = "main", trigger = "registration_complete")
object Graph

@SightScreen(subgraph = "main", isRoot = true)
@SightTransition(toSubgraph = "profile", trigger = "profile_tap")
@SightTransition(toScreen = "transaction_details", trigger = "transaction_tap")
@Composable
@Preview
fun Main() {}

@SightScreen(subgraph = "profile", isRoot = true, id = "profile")
@Composable
@Preview
fun Profile() {}

@SightScreen(subgraph = "main", id = "transaction_details")
@Composable
@Preview
fun TransactionDetails() {}
```

### Module 2

```kotlin
@SightScreen(subgraph = "onboarding", isRoot = true, id = "onboarding")
@SightScreen(subgraph = "onboarding_dark", isRoot = true, id = "onboarding")
@SightTransition(fromSubgraph = "onboarding", toScreen = "login", trigger = "continue")
@Composable
@Preview
fun Onboarding() {}

@SightScreen(subgraph = "onboarding", id = "login")
@Composable
@Preview
fun Login() {}
```

Here are two subgraphs:
- `onboarding` with root node `onboarding`" connected to `login`
- `onboarding_dark` with root node `onboarding_dark`, not connected

### Module 3

```kotlin
@SightScreen(subgraph = "registration", isRoot = true)
@SightTransition(toScreen = "account_details", trigger = "continue")
@Composable
@Preview
fun Email() {}

@SightScreen(subgraph = "registration", id = "account_details")
@Composable
@Preview
fun AccountDetails() {}
```

### Module 4 (defines graph_2)

```kotlin
@SightGraph(entrySubgraph = "onboarding_dark", name = "graph_2")
@SightTransition(fromScreen = "onboarding", fromSubgraph = "onboarding", toSubgraph = "main")
object Graph
```

This module defines only the graph object — no screens. `main` is rooted in Module 1 and merged globally, so it is **not** re-declared here. `graph_2`'s entry subgraph `onboarding_dark` is isolated, so with the default `dropUnconnected = true` only the `onboarding_dark` node is included.

### Module 5 (defines graph_3)

```kotlin
@SightGraph(entrySubgraph = "onboarding", dropUnconnected = false, name = "graph_3")
object Graph
```

This module defines only the graph object — no screens. `main` is rooted in Module 1; it is **not** re-declared here. Because `dropUnconnected = false`, every globally-merged subgraph and node is included regardless of connectivity.

In this example, there are three graphs:
- `graph_1` with entry point `onboarding`, which includes the `onboarding` → `login` flow and the `main` → `profile` + `transaction_details` flow. The `onboarding_dark` subgraph is not included in this graph since it is not connected to the entry point.
- `graph_2` with entry point `onboarding_dark`, which includes only the `onboarding_dark` node since it is not connected to any other nodes. The `onboarding` → `login` flow and the `main` → `profile` + `transaction_details` flow are not included in this graph since they are not connected to the entry point.
- `graph_3` with entry point `onboarding`, which includes all nodes and subgraphs since `dropUnconnected` is set to false. This graph includes the `onboarding` → `login` flow, the `main` → `profile` + `transaction_details` flow, and the unconnected `onboarding_dark` subgraph.