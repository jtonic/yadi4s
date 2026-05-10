import yadis.di.*

@main
def main =
  import business.*

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

  // Print the context report
  println(appCtx.asReport)

  // Resolve bean references
  val beanRefs = appCtx.refs
  println(s"DatabaseUrl: ${beanRefs.databaseUrl}")
  println(s"MaxConnections: ${beanRefs.maxConnections}")

  // Option 1: Explicitly passing bean references
  beanRefs.userService.getUser("1")(using
    beanRefs.userRepo,
    beanRefs.databaseConfig
  )

  // Option 2: The "Magic" Implicit Resolution (Preferred)
  import appCtx.given
  beanRefs.userService.getUser("2")

  // Option 3: Explicitly passing the Context's Macro Resolver
  beanRefs.userService.listUsers()(using appCtx.resolveBean[UserRepository])

  // Entry point: App.run with all dependencies auto-wired
  beanRefs.app.run()
