import mill._
import mill.modules._
import scalalib._
import ammonite.ops._
import coursier.maven.MavenRepository
import mill.scalalib.{PublishModule, ScalaModule}
import mill.scalalib.publish.{Developer, License, PomSettings, VersionControl}

object VersionOf {
  val `cats-core`   = "2.6.1"
  val `cats-effect` = "2.5.1"
  val monix         = "3.4.0"
  val scalacheck    = "1.15.4"
  val scalatest     = "3.2.10"
  val shapeless     = "2.3.3"
}

/** Common properties for all Scala modules. */
trait SubModule extends ScalaModule {
  override def scalaVersion = "3.0.2"

  override def ivyDeps = Agg(
    ivy"org.typelevel::cats-core:${VersionOf.`cats-core`}",
    ivy"org.typelevel::cats-effect:${VersionOf.`cats-effect`}"
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
    "-language:Scala2"
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
    ivy"com.chuusai::shapeless:${VersionOf.shapeless}".withDottyCompat(scalaVersion()),
    ivy"io.monix::monix:${VersionOf.monix}"
  )
  object specs extends SpecsModule
  object props extends PropsModule
}
