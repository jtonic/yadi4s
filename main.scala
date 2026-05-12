@main
def main =
  import yadis.di.*
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

  println(appCtx.asReport)

  import appCtx.given
  val app: App = appCtx.refs.app
  app.run()
