# yadi4s

## Description

TypeSafe DSL DI library for Scala 3

## Usages

See the sneak peek into what it offers

```Scala
@main
def main =
  import yadis.di.* // import yadis API
  import business.* // this is client side business API

  val appCtx =
    ctx:
      configuration("Infrastructure"):
        bean(name = "databaseUrl") { "jdbc:postgresql://localhost:5432/mydb" }
        bean(name = "maxConnections") { 10 }
        bean(name = "databaseConfig") {
          DatabaseConfig("jdbc:postgresql://localhost:5432/mydb", 10)
        }
        bean(name = "userRepo") { new UserRepositoryImpl }
      configuration("Application"):
        bean(name = "userService") { new UserServiceImpl }
        bean(name = "app") { new App }

  println(appCtx.asReport)

  import appCtx.given
  val app: App = appCtx.refs.app
  app.run()
```

## How to see the presentation

See [here](./presentation/README.md)
