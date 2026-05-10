import scala.collection.mutable.ListBuffer
import scala.compiletime.{summonFrom, error}
import scala.quoted.*

object yadis:
  object di:
    case class Ctx(configurations: List[Configuration], beans: Set[Bean[?]]):
      override def toString: String =
        s"Ctx(configurations=[${configurations.map(_.toString).mkString(", ")}], beans=$beans)"

      inline given resolveBean[T]: T = ${ resolveBeanImpl[T]('this) }

    case class Configuration(name: String, beans: List[Bean[?]]):
      override def toString: String =
        s"Configuration(name=$name, beans=[${beans.map(_.toString).mkString(", ")}])"
    case class Bean[T](name: String, value: T):
      override def toString: String = s"Bean(name=$name, value=$value)"

    trait Refs extends Selectable:
      def selectDynamic(name: String): Any

    class RefsImpl(ctx: Ctx) extends Refs:
      def selectDynamic(name: String): Any =
        ctx.beans
          .find(_.name == name)
          .map(_.value)
          .getOrElse(
            throw new NoSuchElementException(s"Bean $name not found")
          )

    extension (inline ctx: Ctx)
      transparent inline def refs: Any = ${ refsMacro('ctx) }

    def refsMacro(ctxExpr: Expr[Ctx])(using Quotes): Expr[Any] =
      import quotes.reflect.*

      val term = ctxExpr.asTerm.underlyingArgument

      def getRhsTree(t: Tree): Tree = t match
        case Inlined(_, _, body) => getRhsTree(body)
        case TypeApply(expr, _)  => getRhsTree(expr)
        case Select(expr, _)     => getRhsTree(expr)
        case Typed(expr, _)      => getRhsTree(expr)
        case Block(_, expr)      => getRhsTree(expr)
        case i @ Ident(_) =>
          i.symbol.tree match
            case ValDef(_, _, Some(rhs)) => getRhsTree(rhs)
            case _                       => t
        case _ => t

      val rhsTree = getRhsTree(term)

      var beanDefinitions = List.empty[(String, TypeRepr)]

      val accumulator = new TreeAccumulator[Unit] {
        override def foldTree(x: Unit, tree: Tree)(owner: Symbol): Unit =
          tree match {
            case Apply(
                  Apply(Apply(TypeApply(fun, List(typeTree)), args1), args2),
                  args3
                ) if fun.symbol.name == "bean" =>
              def extractString(t: Tree): Option[String] = t match
                case Literal(StringConstant(name)) => Some(name)
                case NamedArg(_, arg)              => extractString(arg)
                case Inlined(_, _, arg)            => extractString(arg)
                case Block(_, expr)                => extractString(expr)
                case Typed(expr, _)                => extractString(expr)
                case _                             => None

              val beanNameOpt = args1.flatMap(extractString).headOption

              beanNameOpt.foreach { name =>
                beanDefinitions = beanDefinitions :+ (name, typeTree.tpe)
              }
              foldOverTree(x, tree)(owner)
            case _ =>
              foldOverTree(x, tree)(owner)
          }
      }

      accumulator.foldTree((), rhsTree)(Symbol.spliceOwner)

      val baseType = TypeRepr.of[Refs]

      val refinedType = beanDefinitions.foldLeft(baseType) {
        case (currentType, (name, tpe)) =>
          Refinement(currentType, name, tpe)
      }

      refinedType.asType match
        case '[t] =>
          '{ new RefsImpl($ctxExpr).asInstanceOf[t] }

    // opaque types with construction methods (apply) and companion objects
    opaque type CtxBuilder = ListBuffer[ConfigurationBuilder]
    object CtxBuilder:
      def apply() = ListBuffer.empty[ConfigurationBuilder]
    opaque type ConfigurationBuilder = ConfigurationBuilder.Internal
    object ConfigurationBuilder:
      case class Internal(name: String, beans: ListBuffer[Bean[?]])
      def apply(name: String): ConfigurationBuilder =
        Internal(name, ListBuffer.empty[Bean[?]])

    extension (builder: ConfigurationBuilder)
      def name: String = builder.name
      def beans: ListBuffer[Bean[?]] = builder.beans

    extension (ctx: Ctx)
      def asReport: String =
        ctx.configurations.zipWithIndex
          .map:
            case (config, configIndex) =>
              val beansReport = config.beans.zipWithIndex
                .map { case (bean, beanIndex) =>
                  s"    Bean ${beanIndex + 1}: ${bean.name}"
                }
                .mkString("\n")
              s"Configuration ${configIndex + 1}: ${config.name}:\n$beansReport"
          .mkString("\n\n")

    extension (ctxBuilder: CtxBuilder)
      def toCtx =
        val configs = ctxBuilder
          .map(configBuilder =>
            Configuration(configBuilder.name, configBuilder.beans.toList)
          )
          .toList
        val allBeans = configs.flatMap(_.beans).toSet
        Ctx(configs, allBeans)

    // implicit conversion (don't abuse)
    given ctxBuilderProvider: Conversion[CtxBuilder, Ctx] = builder =>
      val configs = builder
        .map(configBuilder =>
          Configuration(configBuilder.name, configBuilder.beans.toList)
        )
        .toList
      val allBeans = configs.flatMap(_.beans).toSet
      Ctx(configs, allBeans)

    // --- Abstract Nesting Restriction as Extension Method ---
    inline def checkNoNested[T](inline errorMessage: String)(
        inline block: => Unit
    ): Unit =
      summonFrom:
        case given T => error(errorMessage)
        case _       => block

    // Context functions
    def ctx(init: CtxBuilder ?=> Unit): Ctx =
      given builder: CtxBuilder = CtxBuilder()
      init
      builder

    // Metaprogramming with inline functions and scala compile time package
    inline def configuration(name: String)(
        inline init: ConfigurationBuilder ?=> Unit
    )(using ctxBuilder: CtxBuilder): Unit =
      checkNoNested[ConfigurationBuilder](
        "`configuration` cannot be nested inside another `configuration`"
      ):
        given builder: ConfigurationBuilder = ConfigurationBuilder(name)
        init
        ctxBuilder += builder

    def bean[T](name: String)(instance: => T)(using
        configurationBuilder: ConfigurationBuilder
    ): Unit =
      configurationBuilder.beans += Bean(name, instance)

    def resolveBeanImpl[T: Type](ctxExpr: Expr[Ctx])(using Quotes): Expr[T] =
      import quotes.reflect.*

      val term = ctxExpr.asTerm.underlyingArgument

      def getRhsTree(t: Tree): Tree = t match
        case Inlined(_, _, body) => getRhsTree(body)
        case TypeApply(expr, _)  => getRhsTree(expr)
        case Select(expr, _)     => getRhsTree(expr)
        case Typed(expr, _)      => getRhsTree(expr)
        case Block(_, expr)      => getRhsTree(expr)
        case i @ Ident(_) =>
          i.symbol.tree match
            case ValDef(_, _, Some(rhs)) => getRhsTree(rhs)
            case _                       => t
        case _ => t

      val rhsTree = getRhsTree(term)

      var beanDefinitions = List.empty[(String, TypeRepr)]

      val accumulator = new TreeAccumulator[Unit] {
        override def foldTree(x: Unit, tree: Tree)(owner: Symbol): Unit =
          tree match {
            case Apply(
                  Apply(Apply(TypeApply(fun, List(typeTree)), args1), args2),
                  args3
                ) if fun.symbol.name == "bean" =>
              def extractString(t: Tree): Option[String] = t match
                case Literal(StringConstant(name)) => Some(name)
                case NamedArg(_, arg)              => extractString(arg)
                case Inlined(_, _, arg)            => extractString(arg)
                case Block(_, expr)                => extractString(expr)
                case Typed(expr, _)                => extractString(expr)
                case _                             => None

              val beanNameOpt = args1.flatMap(extractString).headOption

              beanNameOpt.foreach { name =>
                beanDefinitions = beanDefinitions :+ (name, typeTree.tpe)
              }
              foldOverTree(x, tree)(owner)
            case _ =>
              foldOverTree(x, tree)(owner)
          }
      }

      accumulator.foldTree((), rhsTree)(Symbol.spliceOwner)

      val targetType = TypeRepr.of[T]
      val matchingBeans = beanDefinitions.filter { case (_, tpe) =>
        tpe <:< targetType
      }

      matchingBeans match
        case Nil =>
          report.errorAndAbort(
            s"No bean found matching type ${targetType.show}"
          )
        case (name, _) :: Nil =>
          '{
            new RefsImpl($ctxExpr)
              .selectDynamic(${ Expr(name) })
              .asInstanceOf[T]
          }
        case multiple =>
          val names = multiple.map(_._1).mkString(", ")
          report.errorAndAbort(
            s"Ambiguous dependency for type ${targetType.show}. Found matching beans: $names"
          )
