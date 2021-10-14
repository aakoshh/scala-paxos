import mill._
import mill.modules._
import scalalib._
import ammonite.ops._
import coursier.maven.MavenRepository
import mill.scalalib.{PublishModule, ScalaModule}
import mill.scalalib.publish.{Developer, License, PomSettings, VersionControl}

object VersionOf {
  val `better-monadic-for` = "0.3.1"
  val cats                 = "2.3.1"
  val monix                = "3.3.0"
  val scalacheck           = "1.15.2"
  val scalatest            = "3.2.5"
  val shapeless            = "2.3.3"
}

/** Common properties for all Scala modules. */
trait SubModule extends ScalaModule {
  override def scalaVersion = "2.13.4"

  override def ivyDeps = Agg(
    ivy"org.typelevel::cats-core:${VersionOf.cats}",
    ivy"org.typelevel::cats-effect:${VersionOf.cats}"
  )

  override def scalacPluginIvyDeps = Agg(
    ivy"com.olegpy::better-monadic-for:${VersionOf.`better-monadic-for`}"
  )

  override def repositories = super.repositories ++ Seq(
    MavenRepository("https://oss.sonatype.org/content/repositories/snapshots")
  )

  override def scalacOptions = Seq(
    "-unchecked",
    "-deprecation",
    "-feature",
    "-encoding",
    "utf-8",
    "-Xfatal-warnings",
    "-Ywarn-value-discard"
  )

  // `extends Tests` uses the context of the module in which it's defined
  trait TestModule extends Tests {
    override def scalacOptions =
      SubModule.this.scalacOptions
  }

  // ScalaTest test
  trait SpecsModule extends TestModule {
    override def testFramework = "org.scalatest.tools.Framework"

    override def ivyDeps = Agg(
      ivy"org.scalatest::scalatest:${VersionOf.scalatest}"
    )

    def single(args: String*) = T.command {
      super.runMain("org.scalatest.run", args: _*)
    }
  }

  // ScalaCheck test.
  trait PropsModule extends TestModule {
    override def testFramework = "org.scalacheck.ScalaCheckFramework"

    override def ivyDeps = Agg(
      ivy"org.scalacheck::scalacheck:${VersionOf.scalacheck}"
    )

    def single(args: String*) = T.command {
      super.runMain(args.head, args.tail: _*)
    }
  }
}

object paxos extends SubModule {
  override def ivyDeps = super.ivyDeps() ++ Agg(
    ivy"com.chuusai::shapeless:${VersionOf.shapeless}",
    ivy"io.monix::monix:${VersionOf.monix}"
  )
  object specs extends SpecsModule
  object props extends PropsModule
}
