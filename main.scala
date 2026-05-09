import Dsl.Beans.*

object Business:
  case class DatabaseConfig(url: String, maxConnections: Int)

  case class User(id: String, name: String, email: String)

  trait UserRepository:
    def findById(id: String): Option[User]
    def findAll(): Seq[User]

  class UserRepositoryImpl extends UserRepository:
    def findById(id: String): Option[User] =
      Some(User(id, "Magda", "magda@yadi4s.dev"))
    def findAll(): Seq[User] =
      Seq(
        User("1", "Magda", "magda@yadi4s.dev"),
        User("2", "Anto", "anto@yadi4s.dev")
      )

  trait UserService:
    def getUser(id: String)(using UserRepository, DatabaseConfig): Option[User]
    def listUsers()(using UserRepository): Seq[User]

  class UserServiceImpl extends UserService:
    def getUser(
        id: String
    )(using repo: UserRepository, db: DatabaseConfig): Option[User] =
      println(s"Querying ${db.url} (pool=${db.maxConnections})")
      repo.findById(id)
    def listUsers()(using repo: UserRepository): Seq[User] =
      repo.findAll()

  class App:
    def run()(using UserService, UserRepository, DatabaseConfig): Unit =
      println("--- App running ---")
      val user = summon[UserService].getUser("1")
      println(s"Found user: $user")
      val all = summon[UserService].listUsers()
      println(s"All users: $all")

@main
def main =
  import Business.*

  val appCtx: Ctx =
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
