package scala.tools.nsc

import java.io.File
import java.nio.file._
import java.util.concurrent.TimeUnit
import java.util.stream.Collectors

import org.openjdk.jmh.annotations.Mode._
import org.openjdk.jmh.annotations._

import scala.tools.benchmark.BenchmarkDriver

trait BaseBenchmarkDriver {
  def source: String
  def extraArgs: String
  def extras: List[String] = if (extraArgs != null && extraArgs != "") extraArgs.split('|').toList else Nil
  def allArgs: List[String] = compilerArgs ++ extras ++ sourceFiles
  def corpusVersion: String
  def depsClasspath: String
  def tempDir: File
  def corpusSourcePath: Path
  def compilerArgs: List[String]
  def sourceFiles: List[String]
  def isResident: Boolean = false
}

@State(Scope.Benchmark)
class ScalacBenchmark extends BenchmarkDriver {
  @Param(value = Array())
  var source: String = _

  @Param(value = Array(""))
  var extraArgs: String = _

  // This parameter is set by ScalacBenchmarkRunner / UploadingRunner based on the Scala version.
  // When running the benchmark directly the "latest" symlink is used.
  @Param(value = Array("latest"))
  var corpusVersion: String = _

  @Param(value = Array("false"))
  var resident: Boolean = false

  override def isResident = resident

  var depsClasspath: String = _

  def compilerArgs: List[String] = if (source.startsWith("@")) source :: Nil else Nil

  def sourceFiles: List[String] =
    if (source.startsWith("@")) Nil
    else {
      import scala.collection.JavaConverters._
      val allFiles = Files.walk(findSourceDir, FileVisitOption.FOLLOW_LINKS).collect(Collectors.toList[Path]).asScala.toList
      val files = allFiles.filter(f => {
        val name = f.getFileName.toString
        name.endsWith(".scala") || name.endsWith(".java")
      }).map(_.toAbsolutePath.normalize.toString)
      files
    }

  var tempDir: File = null

  // Executed once per fork
  @Setup(Level.Trial) def initTemp(): Unit = {
    val tempFile = java.io.File.createTempFile("output", "")
    tempFile.delete()
    tempFile.mkdir()
    tempDir = tempFile
  }
  @TearDown(Level.Trial) def clearTemp(): Unit = {
    BenchmarkUtils.deleteRecursive(tempDir.toPath)
  }

  def corpusSourcePath: Path = Paths.get(s"../corpus/$source/$corpusVersion")

  @Setup(Level.Trial) def initDepsClasspath(): Unit = {
    val classPath = BenchmarkUtils.initDeps(corpusSourcePath)
    if (classPath.nonEmpty) {
      val res = new StringBuilder()
      for (depFile <- classPath) {
        if (res.nonEmpty) res.append(File.pathSeparator)
        res.append(depFile.toAbsolutePath.normalize.toString)
      }
      depsClasspath = res.toString
    }
  }

  private def findSourceDir: Path = {
    val path = corpusSourcePath
    if (Files.exists(path)) path
    else Paths.get(source)
  }
}

// JMH-independent entry point to run the code in the benchmark, for debugging or
// using external profilers.
object ScalacBenchmarkStandalone {
  def main(args: Array[String]): Unit = {
    val bench = new ScalacBenchmark
    bench.source = args(0)
    val iterations = args(1).toInt
    bench.initTemp()
    var i = 0
    while (i < iterations) {
      bench.compileImpl()
      i += 1
    }
    bench.clearTemp()
  }
}

@State(Scope.Benchmark)
@BenchmarkMode(Array(SingleShotTime))
@OutputTimeUnit(TimeUnit.MILLISECONDS)
// TODO -Xbatch reduces fork-to-fork variance, but incurs 5s -> 30s slowdown
@Fork(value = 16, jvmArgs = Array("-XX:CICompilerCount=2", "-Xms2G", "-Xmx2G"))
class ColdScalacBenchmark extends ScalacBenchmark {
  @Benchmark
  def compile(): Unit = compileImpl()
}

@BenchmarkMode(Array(SampleTime))
@OutputTimeUnit(TimeUnit.MILLISECONDS)
@Warmup(iterations = 0)
@Measurement(iterations = 1, time = 30, timeUnit = TimeUnit.SECONDS)
@Fork(value = 3, jvmArgs = Array("-Xms2G", "-Xmx2G"))
class WarmScalacBenchmark extends ScalacBenchmark {
  @Benchmark
  def compile(): Unit = compileImpl()
}

@BenchmarkMode(Array(SampleTime))
@OutputTimeUnit(TimeUnit.MILLISECONDS)
@Warmup(iterations = 10, time = 10, timeUnit = TimeUnit.SECONDS)
@Measurement(iterations = 10, time = 10, timeUnit = TimeUnit.SECONDS)
@Fork(value = 3, jvmArgs = Array("-Xms2G", "-Xmx2G"))
class HotScalacBenchmark extends ScalacBenchmark {
  @Benchmark
  def compile(): Unit = compileImpl()
}
