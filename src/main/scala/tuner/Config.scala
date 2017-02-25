package tuner

import cde._
import chisel3._
import chisel3.experimental._
import craft._
import dsptools._
import dsptools.numbers.{Field=>_,_}
import dsptools.numbers.implicits._
import dspblocks._
import dspjunctions._
import dspblocks._
import _root_.junctions._
import uncore.tilelink._
import uncore.coherence._
import scala.collection.mutable.Map

object TunerConfigBuilder {
  def apply[T <: Data : Ring](
    id: String, tunerConfig: TunerConfig, genIn: () => T, genOut: Option[() => T] = None): Config = new Config(
      (pname, site, here) => pname match {
        case TunerKey(_id) if _id == id => tunerConfig
        case IPXactParameters(_id) if _id == id => {
          val parameterMap = Map[String, String]()
      
          // Conjure up some IPXACT synthsized parameters.
          val gk = site(GenKey(id))
          val inputLanes = gk.lanesIn
          val outputLanes = gk.lanesOut
          val inputTotalBits = gk.genIn.getWidth * inputLanes
          val outputTotalBits = gk.genOut.getWidth * outputLanes
          parameterMap ++= List(
            ("InputLanes", inputLanes.toString),
            ("InputTotalBits", inputTotalBits.toString), 
            ("OutputLanes", outputLanes.toString), 
            ("OutputTotalBits", outputTotalBits.toString),
            ("OutputPartialBitReversed", "1")
          )
      
          // add fractional bits if it's fixed point
          genIn() match {
            case fp: FixedPoint =>
              val fractionalBits = fp.binaryPoint
              parameterMap ++= List(
                ("InputFractionalBits", fractionalBits.get.toString)
              )
            case c: DspComplex[T] =>
              c.underlyingType() match {
                case "fixed" =>
                  val fractionalBits = c.real.asInstanceOf[FixedPoint].binaryPoint
                  parameterMap ++= List(
                    ("InputFractionalBits", fractionalBits.get.toString)
                  )
                case _ => 
              }
            case _ =>
          }
          genOut.getOrElse(genIn)() match {
            case fp: FixedPoint =>
              val fractionalBits = fp.binaryPoint
              parameterMap ++= List(
                ("OutputFractionalBits", fractionalBits.get.toString)
              )
            case c: DspComplex[T] =>
              c.underlyingType() match {
                case "fixed" =>
                  val fractionalBits = c.real.asInstanceOf[FixedPoint].binaryPoint
                  parameterMap ++= List(
                    ("OutputFractionalBits", fractionalBits.get.toString)
                  )
                case _ => 
              }
            case _ =>
          }

          // Coefficients
          //parameterMap ++= pfbConfig.window.zipWithIndex.map{case (coeff, index) => (s"FilterCoefficients$index", coeff.toString)}
          //parameterMap ++= List(("FilterScale", "1"))
      
          // tech stuff, TODO
          parameterMap ++= List(("ClockRate", "100"), ("Technology", "TSMC16nm"))
      
          parameterMap
        }
        case _ => throw new CDEMatchError
      }) ++
  ConfigBuilder.genParams(id, tunerConfig.lanes, genIn, genOutFunc = genOut)
  def standalone[T <: Data : Ring](id: String, tunerConfig: TunerConfig, genIn: () => T, genOut: Option[() => T] = None): Config =
    apply(id, tunerConfig, genIn, genOut) ++
    ConfigBuilder.buildDSP(id, {implicit p: Parameters => new TunerBlock[T]})
}

// default floating point and fixed point configurations
class DefaultStandaloneRealTunerConfig extends Config(TunerConfigBuilder.standalone("tuner", TunerConfig(), () => DspReal()))
class DefaultStandaloneFixedPointTunerConfig extends Config(TunerConfigBuilder.standalone("tuner", TunerConfig(), () => FixedPoint(32.W, 16.BP)))
class DefaultStandaloneComplexTunerConfig extends Config(TunerConfigBuilder.standalone("tuner", TunerConfig(), () => DspComplex(FixedPoint(32.W, 16.BP), FixedPoint(32.W, 16.BP))))

// provides a sample custom configuration
class CustomStandaloneTunerConfig extends Config(TunerConfigBuilder.standalone(
  "tuner", 
  TunerConfig(
    pipelineDepth = 4,
    lanes = 16,
    tunerDepth = 32),
  genIn = () => DspComplex(FixedPoint(18.W, 16.BP), FixedPoint(18.W, 16.BP)),
  genOut = Some(() => DspComplex(FixedPoint(20.W, 16.BP), FixedPoint(20.W, 16.BP)))
))

case class TunerKey(id: String) extends Field[TunerConfig]

trait HasTunerParameters[T <: Data] extends HasGenParameters[T, T] {
   def genCoeff: Option[T] = None
}

case class TunerConfig(
  val pipelineDepth: Int = 0, // pipeline registers are always added at the end
  val lanes: Int = 8, // number of parallel input and output lanes
  val tunerDepth: Int = 16 // how deep the tuner memory is, this times lanes gives total tuner depth
) {
  // sanity checks
  require(pipelineDepth >= 0, "Must have positive pipelining")
  require(tunerDepth >= 0, "Must have positive tuner depth")
  require(lanes > 0, "Must have some input lanes")
}

