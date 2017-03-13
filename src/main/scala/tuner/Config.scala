package tuner

import cde._
import chisel3._
import chisel3.util._
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
  def apply[T <: Data:Ring, V <: Data:Real](
    id: String, tunerConfig: TunerConfig, genIn: () => T, genOut: () => DspComplex[V], genCoeffFunc: Option[() => DspComplex[V]] = None): Config = new Config(
      (pname, site, here) => pname match {
        case TunerKey(_id) if _id == id => tunerConfig
        case TunerGenKey(_id) if _id == id => new TunerGenParameters {
          val inputTotalBits = {
            genIn() match {
              case fp: FixedPoint => fp.getWidth
              case c: DspComplex[T] => c.real.getWidth // assume real and imag have equal total widths
              case d: DspReal => d.getWidth
              case s: SInt => s.getWidth
              case _ =>
                throw new DspException("Unknown input type for tuner")
            }
          }
          // per the spec, defaults to these values
          def genCoeff[T <: Data] = genCoeffFunc.getOrElse( () => DspComplex(FixedPoint(inputTotalBits.W, (inputTotalBits-2).BP), FixedPoint(inputTotalBits.W, (inputTotalBits-2).BP)) )().asInstanceOf[T]
        }
        case IPXactParameters(_id) if _id == id => {
          val parameterMap = Map[String, String]()
      
          // Conjure up some IPXACT synthsized parameters.
          val gk = site(GenKey(id))
          val tgk = site(TunerGenKey(id))
          val config = site(TunerKey(id))
          val inputLanes = gk.lanesIn
          val outputLanes = gk.lanesOut
          parameterMap ++= List(
            ("InputLanes", inputLanes.toString),
            ("OutputTotalBits", (gk.genOut.getWidth/2).toString), // div 2 because must be complex
            ("PhaseGenerator", config.phaseGenerator)
          )
          var inputComplex = "0"

          val inputTotalBits = {
            genIn() match {
              case fp: FixedPoint => fp.getWidth
              case c: DspComplex[T] => c.real.getWidth // assume real and imag have equal total widths
              case d: DspReal => d.getWidth
              case s: SInt => s.getWidth
              case _ =>
                throw new DspException("Unknown input type for tuner")
            }
          }
      
          // add fractional bits if it's fixed point
          genIn() match {
            case fp: FixedPoint =>
              val fractionalBits = fp.binaryPoint
              parameterMap ++= List(
                ("InputFractionalBits", fractionalBits.get.toString),
                ("InputTotalBits", fp.getWidth.toString)
              )
            case c: DspComplex[T] =>
              inputComplex = "1"
              parameterMap ++= List(
                ("InputTotalBits", c.real.getWidth.toString) // assume real and imag have equal total widths
              )
              c.underlyingType() match {
                case "fixed" =>
                  val fractionalBits = c.real.asInstanceOf[FixedPoint].binaryPoint
                  parameterMap ++= List(
                    ("InputFractionalBits", fractionalBits.get.toString)
                  )
                case _ => 
              }
            case d: DspReal =>
              parameterMap ++= List(
                ("InputTotalBits", d.getWidth.toString)
              )
            case s: SInt => 
              parameterMap ++= List(
                ("InputTotalBits", s.getWidth.toString)
              )
            case _ =>
              throw new DspException("Unknown input type for tuner")
          }
          // must be complex
          genOut() match {
            case c: DspComplex[V] =>
              c.underlyingType() match {
                case "fixed" =>
                  val fractionalBits = c.real.asInstanceOf[FixedPoint].binaryPoint
                  parameterMap ++= List(
                    ("OutputFractionalBits", fractionalBits.get.toString)
                  )
                case _ => 
              }
            case _ =>
              throw new DspException("Tuner must have complex outputs")
          }
          parameterMap ++= List(
            ("InputComplex", inputComplex)
          )

          // Coefficients
          genCoeffFunc match {
            case None => 
              parameterMap ++= List(
                ("MixerTableTotalBits", inputTotalBits.toString),
                ("MixerTableFractionalBits", (inputTotalBits-2).toString)
              )
            case Some(c) => { c() match {
              case d: DspComplex[V] => {
                parameterMap ++= List(
                  ("MixerTableTotalBits", d.real.getWidth.toString)
                )
                d.underlyingType() match {
                  case "fixed" => {
                    val fractionalBits = d.real.asInstanceOf[FixedPoint].binaryPoint
                    parameterMap ++= List(
                      ("MixerTableFractionalBits", fractionalBits.get.toString)
                    )
                  }
                  case _ =>
                    throw new DspException("Tuner must have known binary point on coeff")
                }
              }
              case _ =>
                throw new DspException("Tuner must have complex coeff")
            }}
          }

          if (config.phaseGenerator == "Fixed") {
            parameterMap ++= List(
              ("MixerTableSize", config.mixerTableSize.toString)
            )
          }
          
          // tech stuff, TODO
          parameterMap ++= List(("ClockRate", "100"), ("Technology", "TSMC16nm"))
      
          parameterMap
        }
        case _ => throw new CDEMatchError
      }) ++
  ConfigBuilder.genParams(id, tunerConfig.lanes, genIn, genOutFunc = Some(genOut))
  def standalone[T <: Data:Ring, V <: Data:Real](id: String, tunerConfig: TunerConfig, genIn: () => T, genOut: () => DspComplex[V], genCoeff: Option[() => DspComplex[V]] = None)(implicit ev: spire.algebra.Module[DspComplex[V],T]): Config =
    apply(id, tunerConfig, genIn, genOut, genCoeff) ++
    ConfigBuilder.buildDSP(id, {implicit p: Parameters => new TunerBlock[T, V]})
}

trait ComplexRealModule[T <: Data] extends spire.algebra.Module[DspComplex[T], T] {
  implicit def ev: Real[T]
  def timesl(r: T, v: DspComplex[T]): DspComplex[T] = {
    val x = v.real * r
    val y = v.imag * r
    val cmplx = Wire(DspComplex(x, y))
    cmplx.real := x
    cmplx.imag := y
    cmplx
  }

  // Members declared in spire.algebra.AdditiveGroup
  def negate(x: dsptools.numbers.DspComplex[T]): dsptools.numbers.DspComplex[T] = ???
  
  // Members declared in spire.algebra.AdditiveMonoid
  def zero: dsptools.numbers.DspComplex[T] = ???
  
  // Members declared in spire.algebra.AdditiveSemigroup
  def plus(x: dsptools.numbers.DspComplex[T],y: dsptools.numbers.DspComplex[T]): dsptools.numbers.DspComplex[T] = ???
  
  // Members declared in spire.algebra.Module
  implicit def scalar: spire.algebra.Rng[T] = ???
  implicit def ComplexComplexModuleImpl[T<:Data:Real] = new ComplexComplexModule[T] { def ev: Real[T] = Real[T] }
}

trait ComplexComplexModule[T <: Data] extends spire.algebra.Module[DspComplex[T], DspComplex[T]] {
  implicit def ev: Real[T]
  // [stevo]: not sure why * isn't being imported
  def timesl(r: DspComplex[T], v: DspComplex[T]): DspComplex[T] = {
    val real = v.real * r.real - v.imag * r.imag
    val imag = v.real * r.imag + v.imag * r.real
    val cmplx = Wire(DspComplex(real, imag))
    cmplx.real := real
    cmplx.imag := imag
    cmplx
  }

  // Members declared in spire.algebra.AdditiveGroup
  def negate(x: dsptools.numbers.DspComplex[T]): dsptools.numbers.DspComplex[T] = ???
  
  // Members declared in spire.algebra.AdditiveMonoid
  def zero: dsptools.numbers.DspComplex[T] = ???
  
  // Members declared in spire.algebra.AdditiveSemigroup
  def plus(x: dsptools.numbers.DspComplex[T],y: dsptools.numbers.DspComplex[T]): dsptools.numbers.DspComplex[T] = ???
  
  // Members declared in spire.algebra.Module
  implicit def scalar: spire.algebra.Rng[dsptools.numbers.DspComplex[T]] = ???
  implicit def ComplexComplexModuleImpl[T<:Data:Real] = new ComplexComplexModule[T] { def ev: Real[T] = Real[T] }
}

object ComplexModuleImpl {
  implicit def ComplexRealModuleImpl[T<:Data:Real] = new ComplexRealModule[T] { def ev: Real[T] = Real[T] }
  implicit def ComplexComplexModuleImpl[T<:Data:Real] = new ComplexComplexModule[T] { def ev: Real[T] = Real[T] }
}

import ComplexModuleImpl._

// default floating point and fixed point configurations
class DefaultStandaloneRealTunerConfig extends Config(TunerConfigBuilder.standalone("tuner", TunerConfig(), () => DspReal(), () => DspComplex(DspReal(), DspReal())))
class DefaultStandaloneFixedPointTunerConfig extends Config(TunerConfigBuilder.standalone("tuner", TunerConfig(), () => FixedPoint(32.W, 16.BP), () => DspComplex(FixedPoint(32.W, 16.BP), FixedPoint(32.W, 16.BP))))
class DefaultStandaloneComplexTunerConfig extends Config(TunerConfigBuilder.standalone("tuner", TunerConfig(), () => DspComplex(FixedPoint(32.W, 16.BP), FixedPoint(32.W, 16.BP)), () => DspComplex(FixedPoint(32.W, 16.BP), FixedPoint(32.W, 16.BP))))

// provides a sample custom configuration
class CustomStandaloneTunerConfig extends Config(TunerConfigBuilder.standalone(
  "tuner", 
  TunerConfig(
    pipelineDepth = 4,
    lanes = 16,
    phaseGenerator = "Fixed", // "Fixed" or "SimpleFixed"
    mixerTableSize = 32),
  genIn = () => DspComplex(FixedPoint(18.W, 16.BP), FixedPoint(18.W, 16.BP)),
  genOut = () => DspComplex(FixedPoint(20.W, 16.BP), FixedPoint(20.W, 16.BP)),
  genCoeff = Some(() => DspComplex(FixedPoint(21.W, 19.BP), FixedPoint(21.W, 19.BP)))
))

case class TunerKey(id: String) extends Field[TunerConfig]
case class TunerGenKey(id: String) extends Field[TunerGenParameters]

trait TunerGenParameters {
   def genCoeff[T<:Data]: T
}

// T = input, V = output and coeff
trait HasTunerGenParameters[T <: Data, V <: Data] extends HasGenParameters[T, V] {
  def tunerGenExternal = p(TunerGenKey(p(DspBlockId)))
  def genCoeff(dummy: Int = 0) = tunerGenExternal.genCoeff[V]
}

case class TunerConfig(
  pipelineDepth: Int = 0, // pipeline registers are always added at the end
  lanes: Int = 8, // number of parallel input and output lanes
  phaseGenerator: String = "SimpleFixed", // Fixed = 1 coefficient per lane, SimpleFixed = mixerTableSize coefficients per lane
  mixerTableSize: Int = 8, // how deep the tuner memory is, this times lanes gives total tuner depth
  shrink: Double = 1.0 // multiplier to the mixer table constants
) {
  // sanity checks
  require(pipelineDepth >= 0, "Must have positive pipelining")
  require(lanes > 0, "Must have some input lanes")
  require(phaseGenerator == "Fixed" || phaseGenerator == "SimpleFixed", "Phase generator must be either Fixed or SimpleFixed")
  if (phaseGenerator == "Fixed") {
    require(mixerTableSize >= 0, "Must have positive mixer table size")
    require(mixerTableSize%lanes == 0, "Mixer table size must be integer multiple of lanes")
  }

  // only used in Fixed configuration
  val kBits = log2Up(mixerTableSize)
}
