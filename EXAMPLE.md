# Graph definition

## Module 1

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

## Module 2

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

## Module 3

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
