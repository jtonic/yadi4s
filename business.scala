object business:
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
    def run()(using
        userService: UserService,
        userRepository: UserRepository,
        databaseConfig: DatabaseConfig
    ): Unit =
      println("--- App running ---")
      val user = userService.getUser("1")
      println(s"Found user: $user")
      val all = userService.listUsers()
      println(s"All users: $all")
