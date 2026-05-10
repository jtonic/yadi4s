# yadi4s

a TypeSafe DI DSL in Scala 3

---

## The Context

A **Domain-Specific Language** (DSL) is a mini-language tailored to a problem domain

----

Key traits

- **Reads like intent** — declarative, not imperative
- **Domain vocabulary** — method names mirror the problem (`ctx`, `configuration`, `bean`)
- **Type-safe** — the compiler enforces rules at compile time

----

In yadi4s

- you write bean configuration **as if it were a configuration file (yaml)**
- but the Scala compiler validates every reference

---

## The Problem

- Dependency Injection frameworks like Spring DI solve real problems 
- ...but they shift type safety from **compile time** to **runtime**

----

### Runtime failures you've seen

- NoSuchBeanDefinitionException

  ```
  NoSuchBeanDefinitionException: No qualifying bean of type 'PersonRepo'
  ```

- BeanNotOfRequiredTypeException

  ```
  BeanNotOfRequiredTypeException: Bean named 'personRepo' is expected to be 
  of type 'PersonRepo' but was actually of type 'String'
  ```

- NoUniqueBeanDefinitionException

  ```
  NoUniqueBeanDefinitionException: No qualifying bean of type 'NotificationService': 
  expected single matching bean but found 3
  ```

----

- These are **type errors**. 

- In any other context, the compiler catches them. 

- In Spring DI, they become runtime surprises

----

### The root cause

```java
@Configuration
class AppConfig {
    @Bean
    public PersonRepo personRepo() { return new PersonRepoImp(); }

    @Bean
    public NotificationService notifications() { return new NotificationServiceImpl(); }
}
```

----

- Bean names and types are **strings and annotations** — invisible to the type checker
- Wiring happens via **reflection at runtime** — no compile-time validation
- Refactoring a bean name or type? The compiler won't warn you

----

### The cost

| Failure                     | When discovered | Who discovers it          |
| --------------------------- | --------------- | ------------------------- |
| Missing bean                | Startup         | CI / Staging / Production |
| Wrong type                  | Startup         | CI / Staging / Production |
| Ambiguous match             | Startup         | CI / Staging / Production |
| Illegal nesting of configs  | Never (no rule) | Unexpected runtime state  |
| Typo in bean name reference | Runtime         | Production                |

----

**What if none of these could reach runtime?**

---

## The Vision

A DI container where **the compiler is the test suite**:

- Reference a bean that doesn't exist → **compile error**
- Reference a bean with the wrong type → **compile error**
- Two beans match the same type → **compile error**
- Nest a `configuration` inside another → **compile error**
- Typo a bean name → **compile error**

----

### The ideal call site

```scala
import yadis.di.*
@main
def yadi4sMain =
  val appCtx: Ctx =
    ctx:
      configuration("Infrastructure"):
        bean(name = "databaseUrl") { "jdbc:postgresql://localhost:5432/mydb" }
        bean(name = "databaseConfig") { DatabaseConfig("jdbc:postgresql://localhost:5432/mydb", 10) }
        bean(name = "userRepo") { new UserRepositoryImpl }
      configuration("Application"):
        bean(name = "userService") { new UserServiceImpl }
  val beanRefs = appCtx.refs
  beanRefs.databaseUrl       // ✓ type-checked: String
  beanRefs.userRepo          // ✓ type-checked: UserRepository
  beanRefs.databaseConfig    // ✓ type-checked: DatabaseConfig
  beanRefs.nonExistent       // ✗ compile error: not a member
```

- No strings, no casting, **no reflection** — just the Scala compiler doing its job

---

## DSL in Action

----

### Spring DI vs yadi4s

| Concept             | Spring DI (Java)               | yadi4s (Scala 3)               |
| ------------------- | ------------------------------ | ------------------------------ |
| Container           | `ApplicationContext`           | `Ctx`                          |
| Configuration class | `@Configuration class`         | `configuration("name")`        |
| Bean definition     | `@Bean method`                 | `bean(name = "...") { value }` |
| Bean reference      | `@Autowired` / `ctx.getBean()` | `ctx.refs.beanName`            |

----

| Concept             | Spring DI (Java)               | yadi4s (Scala 3)               |
| ------------------- | ------------------------------ | ------------------------------ |
| Injection point     | Constructor / Field            | **Method `using` parameter**   |
| Type safety         | Runtime                        | **Compile time**               |
| Nesting guard       | None                           | **Compile time**               |

----

### Full example

```scala
import yadis.di.*

object business:
  case class DatabaseConfig(url: String, maxConnections: Int)
  case class User(id: String, name: String, email: String)

  trait UserRepository:
    def findById(id: String): Option[User]
    def findAll(): Seq[User]

  class UserRepositoryImpl extends UserRepository:
    def findById(id: String): Option[User] = Some(User(id, "Magda", "magda@yadi4s.dev"))
    def findAll(): Seq[User] = Seq(User("1", "Magda", "magda@yadi4s.dev"), User("2", "Anto", "anto@yadi4s.dev"))

  trait UserService:
    def getUser(id: String)(using UserRepository, DatabaseConfig): Option[User]
    def listUsers()(using UserRepository): Seq[User]

  class UserServiceImpl extends UserService:
    def getUser(id: String)(using repo: UserRepository, db: DatabaseConfig): Option[User] =
      println(s"Querying ${db.url} (pool=${db.maxConnections})")
      repo.findById(id)
    def listUsers()(using repo: UserRepository): Seq[User] = repo.findAll()

@main
def yadi4sMain =
  import business.*
  val appCtx: Ctx =
    ctx:
      configuration("Infrastructure"):
        bean(name = "databaseUrl") { "jdbc:postgresql://localhost:5432/mydb" }
        bean(name = "maxConnections") { 10 }
        bean(name = "databaseConfig") { DatabaseConfig("jdbc:postgresql://localhost:5432/mydb", 10) }
        bean(name = "userRepo") { new UserRepositoryImpl }
      configuration("Application"):
        bean(name = "userService") { new UserServiceImpl }
  println(appCtx.asReport)
```

----

**Output**

```text
Configuration 1: Infrastructure:
    Bean 1: databaseUrl
    Bean 2: maxConnections
    Bean 3: databaseConfig
    Bean 4: userRepo

Configuration 2: Application:
    Bean 1: userService
```

----

### Auto-wiring with `given`

- In yadi4s, injection happens at the **method level** via `using` parameters — not via constructors or field injection

- Dependencies at the method level via `using` parameters

  ```scala
  // UserService declares what it needs on each method
  trait UserService:
    def getUser(id: String)(using UserRepository, DatabaseConfig): Option[User]
  class UserServiceImpl extends UserService:
    def getUser(id: String)(using repo: UserRepository, db: DatabaseConfig): Option[User] =
      println(s"Querying ${db.url} (pool=${db.maxConnections})")
      repo.findById(id)
  ```

----

Injecting dependencies

----

Option 1: The "Magic" Implicit Resolution (Preferred)

  ```scala
  import appCtx.given
  appCtx.refs.userService.getUser("2")
  ```
  
----

Option 2: Explicitly passing bean references

  ```scala
  val beanRefs = appCtx.refs
  beanRefs.userService.getUser("1")(using beanRefs.userRepo, beanRefs.databaseConfig)
  ```

----

Option 3: Explicitly passing the Context's Macro Resolver

  ```scala
  import appCtx.given
  appCtx.refs.userService.listUsers()(using appCtx.resolveBean[UserRepository])
  ```
  
----

Option 4: Local overrides using standard `given` (for testing)

  ```scala
  import appCtx.given
  given DatabaseConfig = DatabaseConfig("jdbc:test", 1)
  beanRefs.userService.getUser("3")
  ```

----

### Why Method-Level Injection?

yadi4s uses **method-level `using` parameters** instead of constructor injection. Why?

- **Decoupled construction** — objects can be defined with partial dependencies
- **Explicit dependencies** — each method declares exactly what it needs
- **Refactoring-friendly** — changing a constructor signature doesn't break the DSL
- **FP-friendly** — local reasoning, the result depends entirely on the inputs
- **Testable** — local overrides via `given` (no mocks)

---

## Type-Safe Access

This is what makes yadi4s more than a builder pattern

Scala macros give you **compile-time guarantees** that Spring DI can only dream of

----

### `refs` — Structural Typing via Macro

The `refs` extension method triggers a macro that:

1. Walks the AST to discover all `bean(...)` calls inside the `Ctx`
2. Extracts each bean's **name** and **type**
3. Builds a **refined structural type** with those names as members
4. Returns an object that the compiler treats as having those exact fields

----

```scala
// What the macro sees:
ctx:
  configuration("Infrastructure"):
    bean(name = "databaseUrl") { "jdbc:postgresql://localhost:5432/mydb" }
    bean(name = "userRepo") { new UserRepositoryImpl }

// What the compiler infers for `appCtx.refs`:
Refs { def databaseUrl: String; def userRepo: UserRepository }
```

----

**At the call site**

```scala
val beanRefs = appCtx.refs

beanRefs.databaseUrl   // ✓ String — autocompleted, type-checked
beanRefs.userRepo      // ✓ UserRepository — autocompleted, type-checked
beanRefs.typosHere     // ✗ compile error: value typosHere is not a member
```

----

**How it works**

```scala
trait Refs extends Selectable:
  def selectDynamic(name: String): Any

class RefsImpl(ctx: Ctx) extends Refs:
  def selectDynamic(name: String): Any =
    ctx.beans.find(_.name == name).map(_.value).getOrElse(...)

extension (inline ctx: Ctx)
  transparent inline def refs: Any = ${ refsMacro('ctx) }

def refsMacro(ctxExpr: Expr[Ctx])(using Quotes): Expr[Any] =
  // 1. Walk AST → collect (name, TypeRepr) pairs
  // 2. Build refined type: Refs { def databaseName: String; ... }
  // 3. Return 'new RefsImpl(ctx).asInstanceOf[t]'
```

----

The key insight 

**`selectDynamic` is dynamic at runtime, but the refined type makes it static at compile time.** 

You get the safety of a typed API with the flexibility of dynamic dispatch

----

### Compile-Time Bean Lookup by Type **resolveBean[T]**

- The `resolveBean` macro answers a single question 

- _given this context, which bean provides type `T`?_

- definition

  ```scala
  inline given resolveBean[T]: T = ${ resolveBeanImpl[T]('this) }
  ```

- call-site

  ```scala
  val userRepo = beanRefs.resolveBean[UserRepository]
  ```

----

**Explanation**

It walks the same AST, but instead of building a structural type, it:

1. Collects all **(name, TypeRepr)** pairs from `bean(...)` calls
2. Filters by **tpe <:< TypeRepr.of[T]** (subtype check)
3. **Zero matches** → compile error: `No bean found matching type T`
4. **One match** → generates `new RefsImpl(ctx).selectDynamic(name).asInstanceOf[T]`
5. **Multiple matches** → compile error: `Ambiguous dependency for type T. Found matching beans: a, b`

----

### Summary: What the Compiler Catches

| Error             | Without macros                   | With `refs` + `resolveBean` |
| ----------------- | -------------------------------- | --------------------------- |
| Typo in bean name | Runtime `NoSuchBean`             | **Compile error**           |
| Wrong type        | Runtime `ClassCastException`     | **Compile error**           |
| Missing bean      | Runtime `NoSuchBeanDefinition`   | **Compile error**           |
| Ambiguous beans   | Runtime `NoUniqueBeanDefinition` | **Compile error**           |

---

## Scala 3 Features — Motivated by DI Needs

Each feature wasn't chosen because it's cool, 

it was chosen because a DI-specific problem demanded it

----

### Opaque Types — "Hide the plumbing"

- Users shouldn't see mutable `ListBuffer` internals. 

- They should only see `CtxBuilder` and `ConfigurationBuilder` as opaque handles

  ```scala
  opaque type CtxBuilder = ListBuffer[ConfigurationBuilder]
  opaque type ConfigurationBuilder = ConfigurationBuilder.Internal
  ```

- **What it gives you:** 
  - The DSL user cannot call `.map()`, `.filter()`, or any `ListBuffer` method on a `CtxBuilder`. 
  - Only the operations the DSL author explicitly provides are available

----

### Context Functions (`?=>`) 

- "Thread the builder silently"

- **Problem:** `ctx` needs to provide a `CtxBuilder`, and `configuration` needs a `ConfigurationBuilder` — but passing them explicitly ruins the DSL syntax

- Without context functions (ugly):
  ```scala
  ctx(builder1 => {
    configuration("Infrastructure", builder1, builder2 => {
      bean("databaseUrl", builder2, "jdbc:postgresql://localhost:5432/mydb")
    })
  })

----

With context functions (clean)

  ```scala
  ctx:
    configuration("Infrastructure"):
      bean(name = "databaseUrl") { "jdbc:postgresql://localhost:5432/mydb" }
  ```

  ```scala
  def ctx(init: CtxBuilder ?=> Unit): Ctx =
    given builder: CtxBuilder = CtxBuilder()
    init
    builder
  ```

- **What it gives you:** 
  - The builder is an implicit parameter — the compiler threads it. 
  - Users write indentation, not parameter passing

----

### Forbid illegal nesting at compile time

**inline** + **summonFrom** + **error**

- **Problem:** Nesting `configuration` inside `configuration` is semantically wrong. 
- In Spring DI, nothing prevents it. In yadi4s, it's a compile error

  ```scala
  ctx:
    configuration("Outer"):
      configuration("Inner"):   // ✗ compile error
        bean(name = "x") { 1 }
  ```

----

Implementation

  ```scala
  inline def checkNoNested[T](inline errorMessage: String)(
      inline block: => Unit
  ): Unit =
    summonFrom:
      case given T => error(errorMessage)   // T already in scope → error
      case _       => block                  // T not in scope → proceed
  ```

- **What it gives you:** 
  - Domain rules become compiler rules. 
  - No runtime check, no exception — the code simply won't compile

----

### Macros (`scala.quoted`) — Type-check bean references

- **Problem:** `ctx.refs.someName` must be type-checked. 
  The compiler needs to know which names exist and what types they have — but the beans are defined dynamically in DSL blocks

- **Solution:** The `refsMacro` and `resolveBeanImpl` macros inspect the AST at compile time, extract bean definitions, and generate code with precise types

----

### Extension Methods — Add domain behaviour without pollution

- **Problem:** `Ctx` is a data type. Adding `asReport` to it directly would couple data to presentation

  ```scala
  extension (ctx: Ctx)
    def asReport: String = ...
  ```

- **What it gives you:** 
  - Domain methods live alongside the type, not inside it
  - The core model stays clean

----

### Implicit Conversion — Builder to result, transparently

- **Problem:** `ctx` internally builds a `CtxBuilder`, but the user expects a `Ctx` back

  ```scala
  given ctxBuilderProvider: Conversion[CtxBuilder, Ctx] = builder =>
    val configs = builder.map(b => Configuration(b.name, b.beans.toList)).toList
    Ctx(configs, configs.flatMap(_.beans).toSet)
  ```

- **What it gives you:** 
  - `def ctx(init: CtxBuilder ?=> Unit): Ctx` — the return type is `Ctx`, not `CtxBuilder`. 
  - The conversion happens automatically

----

### `Selectable` + Structural Types — Dynamic names, static types

- **Problem:** Bean names are defined at the DSL call site — the compiler can't know them in advance. But we still want `refs.databaseName` to type-check

  ```scala
  trait Refs extends Selectable:
    def selectDynamic(name: String): Any
  ```

- **What it gives you:** 
  - Combined with the `refsMacro` (which builds a refined type), this gives you **dot-notation access with full type safety** — no `asInstanceOf`, no `Map[String, Any]`

----

### Optional Braces — Configuration, not code

- **Problem:** Curly braces add visual noise that obscures the declarative intent

- **With braces**
  ```scala
  ctx {
    configuration("Infrastructure") {
      bean("databaseUrl") { "jdbc:postgresql://localhost:5432/mydb" }
    }
  }
  ```

----

**Without braces** (Scala 3):
  ```scala
  ctx:
    configuration("Infrastructure"):
      bean(name = "databaseUrl") { "jdbc:postgresql://localhost:5432/mydb" }
  ```

- **What it gives you:** 
  - The DSL reads like a YAML config file, but it's type-checked by Scala

----

### Feature → DI Need summary

| Scala 3 Feature                   | DI Need                                       |
| --------------------------------- | --------------------------------------------- |
| Opaque types                      | Hide mutable builder internals                |
| Context functions (`?=>`)         | Thread builders without polluting syntax      |
| `inline` + `summonFrom` + `error` | Compile-time nesting guard                    |
| Macros (`scala.quoted`)           | Compile-time bean resolution & type-safe refs |

----

| Scala 3 Feature                   | DI Need                                       |
| --------------------------------- | --------------------------------------------- |
| Extension methods                 | Add domain methods without coupling           |
| Implicit conversion               | Builder → immutable result transparently      |
| `Selectable` + structural types   | Dot-notation bean access with type safety     |
| Optional braces                   | Configuration-like readability                |

---

## Kotlin vs Scala 3 — Type-Safe Builders Compared

- Both Kotlin and Scala 3 enable type-safe builders — but the mechanisms differ significantly


----

### The Kotlin approach

```kotlin
@DslMarker
annotation class BeanDsl

@BeanDsl
class CtxBuilder {
    fun configuration(name: String, init: ConfigurationBuilder.() -> Unit) { ... }
}

@BeanDsl
class ConfigurationBuilder {
    fun <T> bean(name: String, instance: () -> T) { ... }
}

fun ctx(init: CtxBuilder.() -> Unit): Ctx {
    return CtxBuilder().apply(init).build()
}
```

----

- **Lambda with receiver** (`T.() -> Unit`) scopes DSL calls
- **`@DslMarker`** prevents scope mixing — but it's a **runtime annotation**, not a compiler intrinsic
- **No macro system** — cannot generate type-safe `refs` or compile-time `resolveBean`

----

### The Scala 3 approach

```scala
def ctx(init: CtxBuilder ?=> Unit): Ctx = ...
inline def configuration(name: String)(inline init: ConfigurationBuilder ?=> Unit)(using CtxBuilder): Unit = ...

extension (inline ctx: Ctx)
  transparent inline def refs: Any = ${ refsMacro('ctx) }   // macro-generated structural type
```

- **Context functions** scope DSL calls — no receiver type needed
- **`inline` + `summonFrom`** prevents nesting — enforced at compile time, no annotation
- **Macros** generate precise types — `refs.databaseName: String` is a compile-time fact

----

### Comparison

| Aspect                       | Kotlin                                | Scala 3 (yadi4s)                             |
| ---------------------------- | ------------------------------------- | -------------------------------------------- |
| Scoped builder               | Lambda with receiver (`T.() -> Unit`) | Context function (`CtxBuilder ?=> Unit`)     |
| Prevent scope mixing         | `@DslMarker` annotation               | `inline` + `summonFrom` (compiler intrinsic) |
| Nesting guard                | Runtime (best-effort)                 | **Compile time**                             |
| Type-safe bean access        | Not possible (no macros)              | **Macro-generated structural type**          |

----

| Aspect                       | Kotlin                                | Scala 3 (yadi4s)                             |
| ---------------------------- | ------------------------------------- | -------------------------------------------- |
| Compile-time bean resolution | Not possible                          | **`resolveBean[T]` macro**                   |
| Hide internals               | Private constructor                   | Opaque types                                 |
| Builder → result             | `.build()` call                       | Implicit conversion                          |


- **Bottom line:** 
  - Kotlin gives you a clean DSL syntax. 
  - Scala 3 gives you a clean DSL syntax **plus** compile-time guarantees that Kotlin's type system cannot express

---

## Top-Down Walkthrough

The DSL was built **top-down**: 
- start from the desired call site 
- then add language features until it compiles and enforces the rules

----

### Step 1: Write the dream syntax (doesn't compile yet)

```scala
val appCtx: Ctx =
  ctx:
    configuration("Infrastructure"):
      bean(name = "databaseUrl") { "jdbc:postgresql://localhost:5432/mydb" }
```

----

### Step 2: Add context functions — thread the builders

```scala
def ctx(init: CtxBuilder ?=> Unit): Ctx =
  given builder: CtxBuilder = CtxBuilder()
  init
  builder
```

Now `ctx` compiles, but `configuration` inside it doesn't — `CtxBuilder` isn't in scope for `configuration` yet

----

### Step 3: Add `using` clauses — make builders available

```scala
inline def configuration(name: String)(
    inline init: ConfigurationBuilder ?=> Unit
)(using ctxBuilder: CtxBuilder): Unit =
  given builder: ConfigurationBuilder = ConfigurationBuilder(name)
  init
  ctxBuilder += builder
```

Now the DSL compiles — but there's no nesting guard yet

----

### Step 4: Add `inline` + `summonFrom` — forbid nesting

```scala
inline def checkNoNested[T](inline errorMessage: String)(
    inline block: => Unit
): Unit =
  summonFrom:
    case given T => error(errorMessage)
    case _       => block
```

- Call it inside `configuration`:

  ```scala
  checkNoNested[ConfigurationBuilder](
    "`configuration` cannot be nested inside another `configuration`"
  ):
    // ... configuration body
  ```

----

### Step 5: Add opaque types — hide the plumbing

```scala
opaque type CtxBuilder = ListBuffer[ConfigurationBuilder]
opaque type ConfigurationBuilder = ConfigurationBuilder.Internal
```

Users can't call `ListBuffer` methods on `CtxBuilder`. Clean API surface

----

### Step 6: Add implicit conversion — builder to result

```scala
given Conversion[CtxBuilder, Ctx] = builder =>
  // convert mutable builder to immutable Ctx
```

`ctx` returns `Ctx` — users never see `CtxBuilder`

----

### Step 7: Add macros — type-safe bean access

```scala
extension (inline ctx: Ctx)
  transparent inline def refs: Any = ${ refsMacro('ctx) }

inline given resolveBean[T]: T = ${ resolveBeanImpl[T]('this) }
```

Now `refs.beanName` is type-checked, and `resolveBean[T]` resolves beans by type — all at compile time

----

### The layering

```
┌─────────────────────────────────────────┐
│  Macros: type-safe refs + resolveBean   │  ← compile-time guarantees
├─────────────────────────────────────────┤
│  inline + summonFrom: nesting guard     │  ← compile-time constraints
├─────────────────────────────────────────┤
│  Context functions: silent threading    │  ← clean syntax
├─────────────────────────────────────────┤
│  Opaque types: hidden internals         │  ← encapsulation
├─────────────────────────────────────────┤
│  Implicit conversion: builder → result  │  ← seamless API
├─────────────────────────────────────────┤
│  Extension methods: domain behaviour    │  ← readability
└─────────────────────────────────────────┘
```

Each layer exists because the layer above needed it. No feature is gratuitous

---

## What's Next

yadi4s is a proof of concept. A real DI system needs more:

| Feature                              | Why                                                                          | Possible Scala 3 mechanism                                                    |
| ------------------------------------ | ---------------------------------------------------------------------------- | ----------------------------------------------------------------------------- |
| **Qualifiers**                       | Multiple beans of the same type                                              | `bean(name = "x", qualifier = "primary")` — refined in `resolveBean` matching |
| **Scoped lifecycle**                 | Singleton, prototype, request-scoped                                         | Context functions + given scoping                                             |
| **`export` clauses**                 | Flat API from nested objects                                                 | `export Configurations.*; export Beans.*`                                     |

The Scala 3 feature set has plenty of room to grow this DSL beyond its current form — without sacrificing compile-time safety

---

## DSL Pros & Cons

No silver bullet — the right tool depends on the task. Here's how internal DSLs compare to other abstraction choices:

----

### Comparison with Other Abstractions

|                      | Internal DSL                              | Traditional API              | Fluent API                | Framework                         |
| -------------------- | ----------------------------------------- | ---------------------------- | ------------------------- | --------------------------------- |
| **Readability**      | Reads like declarative intent             | Method calls, imperative     | Chained `.then().with()`  | Inversion of control, conventions |
| **Learning curve**   | Steep (must learn the mini-language)      | Low (standard library calls) | Medium                    | High (conventions, lifecycle)     |
| **Type safety**      | Compile-time checks built in              | Varies                       | Varies                    | Often runtime-only                |

----

|                      | Internal DSL                              | Traditional API              | Fluent API                | Framework                         |
| -------------------- | ----------------------------------------- | ---------------------------- | ------------------------- | --------------------------------- |
| **IDE support**      | Full (same host language)                 | Full                         | Full                      | Partial (annotations, config)     |
| **Flexibility**      | Low — constrained by DSL grammar          | High — anything goes         | Medium                    | Low — locked into framework       |
| **Maintenance cost** | High (the DSL itself is code to maintain) | Low                          | Low                       | High                              |
| **Best for**         | Configuration, rules, specs               | General-purpose coding       | Builder patterns, queries | Application architecture          |

---

### Performance Considerations

A DSL is a **wrapper** — it sits on top of existing APIs and inevitably adds overhead:

- **Indirection cost** — each `ctx`, `configuration`, `bean` call adds a layer of function invocation and builder mutation
- **Allocation cost** — builders (`ListBuffer`, intermediate objects) are allocated and then discarded during the conversion phase
- **Features hidden by the DSL** — the underlying APIs may expose capabilities that the DSL doesn't surface (e.g. lazy initialization, scoped lifecycle, ordering guarantees)
- **When it matters** — if the DSL is on a hot path (e.g. request handling), measure first

---

### Trade-off Summary

| Pro                                          | Con                                        |
| -------------------------------------------- | ------------------------------------------ |
| Compile-time domain rule enforcement         | DSL code is a maintenance burden           |
| Self-documenting, readable syntax            | Users must learn the mini-language         |
| Full IDE support (autocomplete, refactoring) | Constrained by host language syntax        |
| No code generation or external tooling       | Wraps and may hide underlying API features |
| Type-safe — errors caught before runtime     | Adds indirection and allocations           |

---

## When to Use a DSL?

### Good Candidates

- **Configuration / wiring** — Spring-style bean contexts, dependency injection graphs
- **Testing / assertions** — declarative spec-like syntax where readability matters
- **Build definitions** — sbt, Gradle Kotlin DSL
- **Routing / HTTP** — path combinators, middleware pipelines
- **Data pipelines** — ETL, stream processing, query builders

The common thread: **declarative intent over imperative steps**, **read over write**, **non-performance-critical paths**

---

## Takeaways

1. **DI failures are type errors** — the compiler should catch them, not the runtime
2. **Scala 3 macros** make it possible to generate precise types from DSL definitions
3. **`inline` + `summonFrom`** turn domain rules into compiler rules
4. **Context functions + opaque types** give you clean syntax without leaking internals
5. **Method-level `using` injection** leverages Scala 3's implicit resolution as a DI mechanism — no framework magic needed
