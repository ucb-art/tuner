// See LICENSE for license details.
package tuner

import diplomacy.{LazyModule, LazyModuleImp}
import dsptools.Utilities._
import dsptools.{DspContext, Grow}
import spire.algebra.{Field, Ring}
import breeze.math.{Complex}
import breeze.linalg._
import breeze.signal._
import breeze.signal.support._
import breeze.signal.support.CanFilter._
import chisel3._
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

class TunerTester[T <: Data](c: TunerBlock[T])(implicit p: Parameters) extends DspBlockTester(c) {
  val config = p(TunerKey)(p)
  val gk = p(GenKey(p(DspBlockId)))
  val test_length = 2
  
  // define input datasets here
  //def input = Seq.fill(test_length)(Seq.fill(gk.lanesIn)(Random.nextDouble*2-1))
  //def input = Seq.fill(test_length)(Seq.fill(gk.lanesIn)(2.718281828))
  val mult = Array.fill(gk.lanesIn)(Random.nextDouble*2-1)

  //def streamIn = packInputStream(input, gk.genIn)
  def input = Seq(BigInt(7), BigInt(6), BigInt(5), BigInt(3))
  def streamIn = input

  // use Breeze FIR filter, but trim (it zero pads the input) and decimate output
  //val expected_output = filter(DenseVector(input.toArray.flatten), DenseVector(filter_coeffs)).toArray.drop(config.numberOfTaps-2).dropRight(config.numberOfTaps-2).grouped(gk.lanesIn/gk.lanesOut).map(_.head).toArray

  // reset 5 cycles
  reset(5)

  pauseStream
  mult.zipWithIndex.foreach { case(x, i) => axiWriteAs(addrmap(s"mult$i"), x, config.genMult.getOrElse(gk.genOut[T])) }
  step(10)
  playStream
  step(test_length)
  val output = unpackOutputStream(gk.genOut[T], gk.lanesOut)

  println("Input:")
  //println(input.toArray.flatten.deep.mkString("\n"))
  println(input.toArray.deep.mkString("\n"))
  println("Tuner Coefficients")
  println(mult.deep.mkString("\n"))
  println("Chisel Output")
  println(output.toArray.deep.mkString("\n"))
  //println("Reference Output")
  //println(expected_output.deep.mkString(","))

  // as an example, though still need to convert from BigInt in bits to double
  //val tap0 = axiRead(0)

  // check within 5%
  //compareOutput(output, expected_output, 5e-2)
}

class TunerSpec extends FlatSpec with Matchers {
  behavior of "Tuner"
  val manager = new TesterOptionsManager {
    testerOptions = TesterOptions(backendName = "firrtl", testerSeed = 7L)
    interpreterOptions = InterpreterOptions(setVerbose = false, writeVCD = true)
  }

  it should "work with DspBlockTester" in {
    implicit val p: Parameters = Parameters.root(new DspConfig().toInstance)
    //implicit object FixedTypeclass extends dsptools.numbers.FixedPointReal { 
    //  override def fromDouble(x: Double): FixedPoint = {
    //    FixedPoint.fromDouble(x, binaryPoint = p(FractionalBits))
    //  }
    //} 
    val dut = () => LazyModule(new LazyTunerBlock[DspReal]).module
    chisel3.iotesters.Driver.execute(dut, manager) { c => new TunerTester(c) } should be (true)
  }
}
