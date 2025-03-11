package chipyard

import org.chipsalliance.cde.config.{Config}
import saturn.common.{VectorParams}

// Rocket-integrated configs
class OPUV256D128RocketConfig extends Config(
  new saturn.rocket.WithRocketVectorUnit(256, 128, VectorParams.opuParams) ++
  new chipyard.config.WithSystemBusWidth(128) ++
  new freechips.rocketchip.rocket.WithNHugeCores(1) ++
  new chipyard.config.AbstractConfig)
class OPUV512D256RocketConfig extends Config(
  new saturn.rocket.WithRocketVectorUnit(512, 256, VectorParams.opuParams) ++
  new chipyard.config.WithSystemBusWidth(256) ++
  new freechips.rocketchip.rocket.WithNHugeCores(1) ++
  new chipyard.config.AbstractConfig)

class OPUV128D128RocketConfig extends Config(
  new saturn.rocket.WithRocketVectorUnit(128, 128, VectorParams.opuParams) ++
  new chipyard.config.WithSystemBusWidth(128) ++
  new freechips.rocketchip.rocket.WithNHugeCores(1) ++
  new chipyard.config.AbstractConfig)
class OPUV256D256RocketConfig extends Config(
  new saturn.rocket.WithRocketVectorUnit(256, 256, VectorParams.opuParams) ++
  new chipyard.config.WithSystemBusWidth(256) ++
  new freechips.rocketchip.rocket.WithNHugeCores(1) ++
  new chipyard.config.AbstractConfig)
class OPUV512D512RocketConfig extends Config(
  new saturn.rocket.WithRocketVectorUnit(512, 512, VectorParams.opuParams) ++
  new chipyard.config.WithSystemBusWidth(512) ++
  new freechips.rocketchip.rocket.WithNHugeCores(1) ++
  new chipyard.config.AbstractConfig)

// Shuttle
class OPUV128D128ShuttleConfig extends Config(
  new saturn.shuttle.WithShuttleVectorUnit(128, 128, VectorParams.opuParams) ++
  new chipyard.config.WithSystemBusWidth(128) ++
  new shuttle.common.WithShuttleTileBeatBytes(16) ++
  new shuttle.common.WithNShuttleCores(1) ++
  new chipyard.config.AbstractConfig)
class OPUV256D128ShuttleConfig extends Config(
  new saturn.shuttle.WithShuttleVectorUnit(256, 128, VectorParams.opuParams) ++
  new chipyard.config.WithSystemBusWidth(128) ++
  new shuttle.common.WithShuttleTileBeatBytes(16) ++
  new shuttle.common.WithNShuttleCores(1) ++
  new chipyard.config.AbstractConfig)
class OPUV256D256ShuttleConfig extends Config(
  new saturn.shuttle.WithShuttleVectorUnit(256, 256, VectorParams.opuParams) ++
  new chipyard.config.WithSystemBusWidth(256) ++
  new shuttle.common.WithShuttleTileBeatBytes(32) ++
  new shuttle.common.WithNShuttleCores(1) ++
  new chipyard.config.AbstractConfig)
class OPUV512D256ShuttleConfig extends Config(
  new saturn.shuttle.WithShuttleVectorUnit(512, 256, VectorParams.opuParams) ++
  new chipyard.config.WithSystemBusWidth(256) ++
  new shuttle.common.WithShuttleTileBeatBytes(32) ++
  new shuttle.common.WithNShuttleCores(1) ++
  new chipyard.config.AbstractConfig)

class OPUV256D256M128ShuttleConfig extends Config(
  new saturn.shuttle.WithShuttleVectorUnit(256, 256, VectorParams.opuParams, mLen = Some(128)) ++
  new chipyard.config.WithSystemBusWidth(128) ++
  new shuttle.common.WithShuttleTileBeatBytes(16) ++
  new shuttle.common.WithNShuttleCores(1) ++
  new chipyard.config.AbstractConfig)
class OPUV512D256M128ShuttleConfig extends Config(
  new saturn.shuttle.WithShuttleVectorUnit(512, 256, VectorParams.opuParams, mLen = Some(128)) ++
  new chipyard.config.WithSystemBusWidth(128) ++
  new shuttle.common.WithShuttleTileBeatBytes(16) ++
  new shuttle.common.WithNShuttleCores(1) ++
  new chipyard.config.AbstractConfig)
class OPUV512D512M256ShuttleConfig extends Config(
  new saturn.shuttle.WithShuttleVectorUnit(512, 512, VectorParams.opuParams, mLen = Some(128)) ++
  new chipyard.config.WithSystemBusWidth(256) ++
  new shuttle.common.WithShuttleTileBeatBytes(32) ++
  new shuttle.common.WithNShuttleCores(1) ++
  new chipyard.config.AbstractConfig)