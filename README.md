Tuner [![Build Status](https://travis-ci.org/ucb-art/tuner.svg?branch=master)](https://travis-ci.org/ucb-art/tuner)
=======================

# Overview

This project contains a digital tuner.

# Usage

## GitHub Pages

See [here](https://ucb-art.github.io/tuner/latest/api/) for the GitHub pages scaladoc.

## Setup

Clone the repository and update the depenedencies:

```
git clone git@github.com:ucb-art/tuner.git
git submodule update --init
cd dsp-framework
./update.bash
cd ..
```

See the [https://github.com/ucb-art/dsp-framework/blob/master/README.md](dsp-framework README) for more details on this infrastructure.
Build the dependencies by typing `make libs`.

## Building

The build flow generates FIRRTL, then generates Verilog, then runs the TSMC memory compiler to generate memories.
Memories are black boxes in the Verilog by default.
IP-Xact is created with the FIRRTL.
The build targets for each of these are firrtl, verilog, and mems, respectively.
Depedencies are handled automatically, so to build the Verilog and memories, just type `make mems`.
Results are placed in a `generated-src` directory.

## Testing

Chisel testing hasn't been implemented yet.

## Configuring

In `src/main/scala` there is a `Config.scala` file.
A few default configurations are defined for you, called DefaultStandaloneXTunerConfig, where X is either Real, FixedPoint, or Complex.
Real and FixedPoint produce floating point and fixed point input versions, respectively, with the outputs and coefficients as complex numbers with underlying types of floating point and fixed point, respectively.
These generate a small tuner with default parameters.
To run them, type `make verilog CONFIG=DefaultStandaloneXTuneConfig`, replacing X with Real, FixedPoint, or Complex.
The default make target is the default FixedPoint configuration.

The suggested way to create a custom configuration is to modify CustomStandaloneTunerConfig, which defines values for all possible parameters.
Then run `make verilog CONFIG=CustomStandaloneTunerConfig` to generate the Verilog.

# Specifications

## Interfaces

The Tuner uses the [https://github.com/ucb-art/rocket-dsp-utils/blob/master/doc/stream.md](DSP streaming interface) (a subset of AXI4-Stream) on both the data input and data output.
The SCRFile contains the mixer table coefficients or fixed tuner multiplier control registers, as well as data set end flag registers.
It is accessible through an AXI4 interface.

## Signaling

### Bits

It is expected that the bits inputs contain time-series data time-multiplexed on the inputs, such that on the first cycle are values x[0], x[1], …, x[p-1], then the next cycle contains x[p], x[p+1], … and this continues indefinitely. 
The outputs are in same time order.
Inputs are either complex or real, but the output and coefficients are always complex.

### Valid

The output valid is just the input valid delayed by the pipeline depth.

### Sync

The output sync is just the input sync delayed by the pipeline depth.

## Implementation

The tuner implements a programmable mixer.
The inputs can be either complex or real.
If the inputs are real, they are mapped to the real part of complex values before being multiplied by the mixer coefficients.
The coefficients and outputs are always complex.

There are loosely two main configuration options for the mixer.
The options are called "SimpleFixed" or "Fixed".
In the SimpleFixed scenario, input lanes are multiplied by programmable phase values defined in the SCRFile.  
The SCRFile provides one phase value for each lane. 
Phase values are always signed and complex.

In the Fixed scenario, the phase values are restricted to being one of 2\*pi/N phases where N is defined by the parameter mixerTableSize.
The mixerTableSize must be an integer multiple of the number of lanes.
The SCRFile allows control of input k, also called FixedTunerMultiplier, which will specify to use phase 0, 2\*pi\*k/N, 2\*2\*pi\*k/N, 3\*2\*pi\*k/N.
This sequence will inherently rollover and repeat after at most N phases and thus each input lane will always use the same value once k is chosen.
The values of sin and cos may be multiplied by a small constant slightly less than 1 (e.g. .999) to prevent rounding or truncation from causing bit growth.
See Figure below.
Note that some lanes may be able to use any phase while other lanes will be restricted to a subset of the phases.
For example, lane 0 will always use a constant of approximately 1 +i\*0.

![SimpleFixed mixer table](/doc/simplemixer.png?raw=true)

