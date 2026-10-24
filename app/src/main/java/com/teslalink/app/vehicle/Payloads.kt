package com.teslalink.app.vehicle

import com.google.protobuf.ByteString
import com.tesla.generated.carserver.common.Common
import com.tesla.generated.carserver.server.CarServer
import com.tesla.generated.keys.Keys
import com.tesla.generated.vcsec.Vcsec

/**
 * Application-layer payloads. Builders mirror pkg/vehicle/{actions,charge,climate,security}.go.
 */
object Payloads {

    fun rkeAction(action: Vcsec.RKEAction_E): Vcsec.UnsignedMessage =
        Vcsec.UnsignedMessage.newBuilder()
            .setRKEAction(action)
            .build()

    fun closureMove(closure: Closure, action: Vcsec.ClosureMoveType_E): Vcsec.UnsignedMessage {
        val request = Vcsec.ClosureMoveRequest.newBuilder()
        when (closure) {
            Closure.REAR_TRUNK -> request.setRearTrunk(action)
            Closure.FRONT_TRUNK -> request.setFrontTrunk(action)
            Closure.TONNEAU -> request.setTonneau(action)
            Closure.CHARGE_PORT -> request.setChargePort(action)
        }
        return Vcsec.UnsignedMessage.newBuilder()
            .setClosureMoveRequest(request)
            .build()
    }

    fun informationRequest(type: Vcsec.InformationRequestType): Vcsec.UnsignedMessage =
        Vcsec.UnsignedMessage.newBuilder()
            .setInformationRequest(
                Vcsec.InformationRequest.newBuilder().setInformationRequestType(type)
            )
            .build()

    fun addKeyPayload(publicKeyBytes: ByteArray, role: Keys.Role): Vcsec.UnsignedMessage =
        Vcsec.UnsignedMessage.newBuilder()
            .setWhitelistOperation(
                Vcsec.WhitelistOperation.newBuilder()
                    .setAddKeyToWhitelistAndAddPermissions(
                        Vcsec.PermissionChange.newBuilder()
                            .setKey(
                                Vcsec.PublicKey.newBuilder()
                                    .setPublicKeyRaw(ByteString.copyFrom(publicKeyBytes))
                            )
                            .setKeyRole(role)
                    )
                    .setMetadataForKey(
                        Vcsec.KeyMetadata.newBuilder()
                            .setKeyFormFactor(Vcsec.KeyFormFactor.KEY_FORM_FACTOR_ANDROID_DEVICE)
                    )
            )
            .build()

    /** Raw ToVCSECMessage envelope used for the BLE add-key request (PRESENT_KEY signature type). */
    fun addKeyRequestEnvelope(publicKeyBytes: ByteArray, role: Keys.Role): Vcsec.ToVCSECMessage =
        Vcsec.ToVCSECMessage.newBuilder()
            .setSignedMessage(
                Vcsec.SignedMessage.newBuilder()
                    .setProtobufMessageAsBytes(
                        ByteString.copyFrom(addKeyPayload(publicKeyBytes, role).toByteArray())
                    )
                    .setSignatureType(Vcsec.SignatureType.SIGNATURE_TYPE_PRESENT_KEY)
            )
            .build()

    private fun vehicleAction(block: CarServer.VehicleAction.Builder.() -> Unit): CarServer.Action =
        CarServer.Action.newBuilder()
            .setVehicleAction(CarServer.VehicleAction.newBuilder().apply(block))
            .build()

    /**
     * `HvacAutoAction` 有两个 bool：power_on 与 manual_override，协议里都不带 optional。
     * 只写 power_on 等于把 manual_override 发成 false —— 也就是**强制回到 AUTO**，
     * 会把他在车上调好的手动风量顶掉。所以开关空调必须把他当前的模式一起回发。
     */
    fun climateOn(manual: Boolean = false): CarServer.Action = vehicleAction {
        setHvacAutoAction(
            CarServer.HvacAutoAction.newBuilder()
                .setPowerOn(true)
                .setManualOverride(manual),
        )
    }

    fun climateOff(): CarServer.Action = vehicleAction {
        setHvacAutoAction(CarServer.HvacAutoAction.newBuilder().setPowerOn(false))
    }

    fun changeClimateTemp(driverCelsius: Float, passengerCelsius: Float): CarServer.Action = vehicleAction {
        setHvacTemperatureAdjustmentAction(
            CarServer.HvacTemperatureAdjustmentAction.newBuilder()
                .setDriverTempCelsius(driverCelsius)
                .setPassengerTempCelsius(passengerCelsius)
                .setLevel(
                    CarServer.HvacTemperatureAdjustmentAction.Temperature.newBuilder()
                        .setTEMPMAX(Common.Void.getDefaultInstance())
                )
        )
    }

    fun chargeStart(): CarServer.Action = vehicleAction {
        setChargingStartStopAction(
            CarServer.ChargingStartStopAction.newBuilder()
                .setStart(Common.Void.getDefaultInstance())
        )
    }

    fun chargeStop(): CarServer.Action = vehicleAction {
        setChargingStartStopAction(
            CarServer.ChargingStartStopAction.newBuilder()
                .setStop(Common.Void.getDefaultInstance())
        )
    }

    fun changeChargeLimit(percent: Int): CarServer.Action = vehicleAction {
        setChargingSetLimitAction(CarServer.ChargingSetLimitAction.newBuilder().setPercent(percent))
    }

    fun setChargingAmps(amps: Int): CarServer.Action = vehicleAction {
        setSetChargingAmpsAction(CarServer.SetChargingAmpsAction.newBuilder().setChargingAmps(amps))
    }

    fun chargePortOpen(): CarServer.Action = vehicleAction {
        setChargePortDoorOpen(CarServer.ChargePortDoorOpen.getDefaultInstance())
    }

    fun chargePortClose(): CarServer.Action = vehicleAction {
        setChargePortDoorClose(CarServer.ChargePortDoorClose.getDefaultInstance())
    }

    fun ventWindows(): CarServer.Action = vehicleAction {
        setVehicleControlWindowAction(
            CarServer.VehicleControlWindowAction.newBuilder()
                .setVent(Common.Void.getDefaultInstance())
        )
    }

    fun closeWindows(): CarServer.Action = vehicleAction {
        setVehicleControlWindowAction(
            CarServer.VehicleControlWindowAction.newBuilder()
                .setClose(Common.Void.getDefaultInstance())
        )
    }

    fun honkHorn(): CarServer.Action = vehicleAction {
        setVehicleControlHonkHornAction(CarServer.VehicleControlHonkHornAction.getDefaultInstance())
    }

    fun flashLights(): CarServer.Action = vehicleAction {
        setVehicleControlFlashLightsAction(CarServer.VehicleControlFlashLightsAction.getDefaultInstance())
    }

    fun setSentryMode(on: Boolean): CarServer.Action = vehicleAction {
        setVehicleControlSetSentryModeAction(
            CarServer.VehicleControlSetSentryModeAction.newBuilder().setOn(on)
        )
    }

    /** level: 0=关 1=低 2=中 3=高。车没装对应座椅时车辆会拒绝执行。 */
    fun seatHeater(level: Int, frontLeft: Boolean): CarServer.Action {
        val v = Common.Void.getDefaultInstance()
        val seat = CarServer.HvacSeatHeaterActions.HvacSeatHeaterAction.newBuilder()
        when (level.coerceIn(0, 3)) {
            0 -> seat.setSEATHEATEROFF(v)
            1 -> seat.setSEATHEATERLOW(v)
            2 -> seat.setSEATHEATERMED(v)
            else -> seat.setSEATHEATERHIGH(v)
        }
        if (frontLeft) seat.setCARSEATFRONTLEFT(v) else seat.setCARSEATFRONTRIGHT(v)
        return vehicleAction {
            setHvacSeatHeaterActions(
                CarServer.HvacSeatHeaterActions.newBuilder().addHvacSeatHeaterAction(seat)
            )
        }
    }

    /** 座椅通风（HvacSeatCoolerLevel 的枚举值从 Off=1 开始，和加热的 0 基错一位）。 */
    fun seatCooler(level: Int, frontLeft: Boolean): CarServer.Action {
        val lvl = when (level.coerceIn(0, 3)) {
            0 -> CarServer.HvacSeatCoolerActions.HvacSeatCoolerLevel_E.HvacSeatCoolerLevel_Off
            1 -> CarServer.HvacSeatCoolerActions.HvacSeatCoolerLevel_E.HvacSeatCoolerLevel_Low
            2 -> CarServer.HvacSeatCoolerActions.HvacSeatCoolerLevel_E.HvacSeatCoolerLevel_Med
            else -> CarServer.HvacSeatCoolerActions.HvacSeatCoolerLevel_E.HvacSeatCoolerLevel_High
        }
        val pos =
            if (frontLeft) CarServer.HvacSeatCoolerActions.HvacSeatCoolerPosition_E.HvacSeatCoolerPosition_FrontLeft
            else CarServer.HvacSeatCoolerActions.HvacSeatCoolerPosition_E.HvacSeatCoolerPosition_FrontRight
        return vehicleAction {
            setHvacSeatCoolerActions(
                CarServer.HvacSeatCoolerActions.newBuilder().addHvacSeatCoolerAction(
                    CarServer.HvacSeatCoolerActions.HvacSeatCoolerAction.newBuilder()
                        .setSeatCoolerLevel(lvl)
                        .setSeatPosition(pos)
                )
            )
        }
    }

    fun steeringWheelHeater(on: Boolean): CarServer.Action = vehicleAction {
        setHvacSteeringWheelHeaterAction(
            CarServer.HvacSteeringWheelHeaterAction.newBuilder().setPowerOn(on)
        )
    }

    /** MAX 预热/除霜。 */
    fun maxDefrost(on: Boolean): CarServer.Action = vehicleAction {
        setHvacSetPreconditioningMaxAction(
            CarServer.HvacSetPreconditioningMaxAction.newBuilder().setOn(on)
        )
    }

    /** mode: "Off"|"On"|"Dog"|"Camp"（保持/爱犬/露营）。 */
    fun climateKeeper(mode: String): CarServer.Action {
        val keeper = when (mode) {
            "On" -> CarServer.HvacClimateKeeperAction.ClimateKeeperAction_E.ClimateKeeperAction_On
            "Dog" -> CarServer.HvacClimateKeeperAction.ClimateKeeperAction_E.ClimateKeeperAction_Dog
            "Camp" -> CarServer.HvacClimateKeeperAction.ClimateKeeperAction_E.ClimateKeeperAction_Camp
            else -> CarServer.HvacClimateKeeperAction.ClimateKeeperAction_E.ClimateKeeperAction_Off
        }
        return vehicleAction {
            setHvacClimateKeeperAction(
                CarServer.HvacClimateKeeperAction.newBuilder().setClimateKeeperAction(keeper)
            )
        }
    }

    /** 仅 HEPA 车型有效，其余车辆会拒绝执行。 */
    fun bioweaponMode(on: Boolean): CarServer.Action = vehicleAction {
        setHvacBioweaponModeAction(
            CarServer.HvacBioweaponModeAction.newBuilder().setOn(on)
        )
    }

    fun ping(): CarServer.Action = vehicleAction {
        setPing(CarServer.Ping.newBuilder().setPingId(1))
    }

    fun getVehicleData(categories: List<Category>): CarServer.Action {
        val get = CarServer.GetVehicleData.newBuilder()
        categories.forEach { c ->
            when (c) {
                Category.CHARGE -> get.setGetChargeState(CarServer.GetChargeState.getDefaultInstance())
                Category.CLIMATE -> get.setGetClimateState(CarServer.GetClimateState.getDefaultInstance())
                Category.DRIVE -> get.setGetDriveState(CarServer.GetDriveState.getDefaultInstance())
                Category.LOCATION -> get.setGetLocationState(CarServer.GetLocationState.getDefaultInstance())
                Category.CLOSURES -> get.setGetClosuresState(CarServer.GetClosuresState.getDefaultInstance())
            }
        }
        return vehicleAction { setGetVehicleData(get) }
    }

    enum class Closure { REAR_TRUNK, FRONT_TRUNK, TONNEAU, CHARGE_PORT }

    enum class Category { CHARGE, CLIMATE, DRIVE, LOCATION, CLOSURES }
}
