// See LICENSE for license details.
package tuner

import diplomacy.{LazyModule, LazyModuleImp}
import dsptools.{DspContext, Grow}
import spire.algebra.{Field, Ring}
import breeze.math.{Complex}
import breeze.linalg._
import breeze.signal._
import breeze.signal.support._
import breeze.signal.support.CanFilter._
import chisel3._
import chisel3.experimental._
import chisel3.util._
import chisel3.iotesters._
import dspjunctions._
import dspblocks._
import firrtl_interpreter.InterpreterOptions
import dsptools.numbers.{DspReal, SIntOrder, SIntRing}
import dsptools.{DspContext, DspTester, Grow}
import org.scalatest.{FlatSpec, Matchers}
import dsptools.numbers.implicits._
import dsptools.numbers.{DspComplex, Real}
import chisel3.testers.BasicTester
import org.scalatest._
import scala.util.Random
import scala.math._
import testchipip._

import cde._
import junctions._
import uncore.tilelink._
import uncore.coherence._

import dsptools._

object LocalTest extends Tag("edu.berkeley.tags.LocalTest")

class TunerTester[T <: Data:Ring, Q <: Data:Real](c: TunerBlockModule[T, Q])(implicit p: Parameters) extends DspBlockTester(c) {
  val config = p(TunerKey(p(DspBlockId)))
  val gk = p(GenKey(p(DspBlockId)))
  val sync_period = 1
  val test_length = 1
  
  // define input datasets here
  //val in = Seq.fill(test_length)(Seq.fill(sync_period)(Seq.fill(gk.lanesIn)(Random.nextDouble*2-1)))
  val ins = Seq.fill(test_length)(Seq.fill(sync_period)(Seq.fill(gk.lanesIn)(1.0)))
  def streamIn = ins.map(packInputStream(_, gk.genIn))

  // reset 5 cycles
  reset(5)

  pauseStream
  if (config.phaseGenerator == "Fixed") {
    axiWrite(addrmap("FixedTunerMultiplier"), 2)
    step(2)
    println(s"multiplier = ${axiRead(addrmap("FixedTunerMultiplier"))}")
  } else {
    //mult.zipWithIndex.foreach { case(x, i) => axiWriteAs(addrmap(s"mult$i"), x, genMult.getOrElse(gk.genOut[T])) }
  }
  playStream
  step(test_length*sync_period)
  val output = unpackOutputStream(gk.genOut, gk.lanesOut)

  println("Input:")
  println(ins.toArray.flatten.deep.mkString("\n"))
  println("Chisel Output")
  println(output.toArray.deep.mkString("\n"))
}

class TunerSpec extends FlatSpec with Matchers {
  behavior of "Tuner"
  val manager = new TesterOptionsManager {
    testerOptions = TesterOptions(backendName = "firrtl", testerSeed = 7L)
    interpreterOptions = InterpreterOptions(setVerbose = false, writeVCD = true)
  }

  import ComplexModuleImpl._

  it should "work with DspBlockTester" in {
    implicit val p: Parameters = Parameters.root(TunerConfigBuilder.standalone(
      "tuner", 
      TunerConfig(
        pipelineDepth = 4,
        lanes = 32,
        phaseGenerator = "Fixed", // "Fixed" or "SimpleFixed"
        mixerTableSize = 32,
        shrink = 1.0),
      genIn = () => FixedPoint(8.W, 7.BP),
      genOut = () => DspComplex(FixedPoint(8.W, 7.BP), FixedPoint(8.W, 7.BP)),
      genCoeff = Some(() => DspComplex(FixedPoint(9.W, 7.BP), FixedPoint(9.W, 7.BP))))
      .toInstance)
    //implicit object FixedTypeclass extends dsptools.numbers.FixedPointReal { 
    //  override def fromDouble(x: Double): FixedPoint = {
    //    FixedPoint.fromDouble(x, binaryPoint = p(FractionalBits))
    //  }
    //} 
    val dut = () => LazyModule(new TunerBlock[FixedPoint, FixedPoint]).module
    chisel3.iotesters.Driver.execute(dut, manager) { c => new TunerTester(c) } should be (true)
  }
}
