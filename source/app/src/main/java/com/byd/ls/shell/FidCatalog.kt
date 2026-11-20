package com.byd.ls.shell

/**
 * fid 直读表（1.4.118）。
 *
 * 来源与验证：DiLink 4.0 实车上 `--dumpstatics android.hardware.bydauto.BYDAutoFeatureIds`
 * 抽出 2897 个符号 fid（tools/fixtures/fids-dilink4.txt），再与 BYDMate 的
 * FidMap 逐条对照 —— 设备号与数值两者完全一致，因此沿用其 (dev, fid) 地址与解码口径。
 * 读数走 helper 的 `--fids`（autoservice txn 9 getIntArray，无效项回退 txn 5/7），
 * 全程只启一次 app_process，129 条一次读完约 0.37 s（实机）。
 */
object FidCatalog {

    enum class Kind {
        INT_RAW, INT_ENUM, INT_SCALED, INT_TEMP_C, INT_TEMP_C_OFS40, INT_KPA,
        INT_PERCENT, INT_RANGE_KM, INT_RPM,
        FLOAT_PERCENT, FLOAT_KWH, FLOAT_KW, FLOAT_VOLT, FLOAT_AMP, FLOAT_LITERS,
    }

    class Fid(
        val name: String,
        val dev: Int,
        val fid: Int,
        val kind: Kind,
        val scale: Double,
        val offset: Double,
        val symbol: String,
    )

    val all: List<Fid> = listOf(
        // SOC：BYDMate 在 Leopard 3 上用 STATISTIC_ELEC_PERCENTAGE（txn 7 float），
        // 但在 DiLink 4.0 上该 fid 恒回 -1.0（不支持）。实车（22:5x，DM-i 25%）三条互证：
        //   1014:1145045040 STATISTIC_SOC_BATTERY_PERCENTAGE = 25
        //   1009:842006544  CHARGING_CHARGE_PERCENT_DD       = 25
        //   1014:1246765118 STATISTIC_ELEC_DRIVING_RANGE      = 32 km（25% × ~128 km 吻合）
        // 于是主口径改 1014:1145045040，充电域那条留作兜底（socCharging，见 FidDirect 归一化）。
        Fid("soc", 1014, 1145045040, Kind.INT_PERCENT, 1.0, 0.0, "Statistic.STATISTIC_SOC_BATTERY_PERCENTAGE"),
        Fid("socCharging", 1009, 842006544, Kind.INT_PERCENT, 1.0, 0.0, "Charging.CHARGING_CHARGE_PERCENT_DD"),
        // 1.4.156：SOC 保电开关（实读 1009:1186988086=2；1=智能、2=强制，待实车再确认一次）
        Fid("socSaveMode", 1009, 1186988086, Kind.INT_ENUM, 1.0, 0.0, "Charging.CHARGING_SOC_SAVE_SWITCH"),
        // SOC 目标值；只做保电模式的交叉校验，不直接当保电开关用
        Fid("socTarget", 1023, 873463848, Kind.INT_PERCENT, 1.0, 0.0, "Setting.SET_DR_SOC_TARGET"),
        // 续航沿用下面已有的 evRangeKm(1246765118=32 km) / fuelRangeKm(1246773304=270 km)：
        // byAddr 以 dev:fid 为键，后出现的条目会覆盖先出现的名字，加同址别名会让读值凭空变 NaN。
        Fid("speed", 1013, -1807745016, Kind.FLOAT_KW, 1.0, 0.0, "Speed.SPEED_AUTO_SPEED"),
        Fid("mileage", 1014, 1246765072, Kind.INT_SCALED, 0.1, 0.0, "Statistic.STATISTIC_TOTAL_MILEAGE"),
        Fid("power", 1012, 339738656, Kind.INT_RAW, 1.0, 0.0, "Engine.ENGINE_POWER"),
        Fid("totalElecConsumption", 1014, 1032871984, Kind.FLOAT_KWH, 1.0, 0.0, "Statistic.STATISTIC_TOTAL_ELEC_CONSUMPTION"),
        Fid("batteryRemainKwh", 1005, 882901008, Kind.FLOAT_KWH, 1.0, 0.0, "Power.POWER_BATTERY_REMAIN_ELECTRICITY"),
        Fid("voltage12v", 1001, 1128267816, Kind.FLOAT_VOLT, 1.0, 0.0, "Ota.OTA_BATTERY_POWER_VOLTAGE"),
        Fid("maxCellVoltage", 1014, 1147142192, Kind.INT_SCALED, 0.001, 0.0, "Statistic.STATISTIC_HIGHEST_BATTERY_VOLTAGE"),
        Fid("minCellVoltage", 1014, 1147142160, Kind.INT_SCALED, 0.001, 0.0, "Statistic.STATISTIC_LOWEST_BATTERY_VOLTAGE"),
        Fid("gear", 1011, 555745336, Kind.INT_ENUM, 1.0, 0.0, "Gearbox.GEARBOX_AUTO_MODE_TYPE"),
        Fid("chargeGunState", 1009, 876609586, Kind.INT_ENUM, 1.0, 0.0, "Charging.CHARGING_GUN_CONNECT_STATE"),
        Fid("bmsState", 1009, 876609560, Kind.INT_ENUM, 1.0, 0.0, "Charging.CHARGING_BATTERRY_DEVICE_STATE"),
        Fid("acStatus", 1000, 1077936144, Kind.INT_ENUM, 1.0, 0.0, "Ac.AC_POWER_STATE"),
        Fid("acTemp", 1000, 1077936168, Kind.INT_TEMP_C, 1.0, 0.0, "Ac.AC_TEMP_MAIN"),
        Fid("fanLevel", 1000, 1077936156, Kind.INT_RAW, 1.0, 0.0, "Ac.AC_WIND_LEVEL"),
        Fid("acCirc", 1000, 1077936148, Kind.INT_ENUM, 1.0, 0.0, "Ac.AC_CYCLE_MODE"),
        Fid("insideTemp", 1000, 1031798832, Kind.INT_TEMP_C, 1.0, 0.0, "Ac.AC_TEMP_INSIDE"),
        Fid("exteriorTemp", 1000, 1077936184, Kind.INT_TEMP_C, 1.0, 0.0, "Ac.AC_TEMP_OUT"),
        Fid("hood", 1001, 692060188, Kind.INT_ENUM, 1.0, 0.0, "Bodywork.BODYWORK_HOOD"),
        Fid("seatbeltFL", 1007, 692060184, Kind.INT_ENUM, 1.0, 0.0, "Instrument.INSTRUMENT_DD_MAIN_SAFETYBELT_STATE"),
        Fid("tirePressFL", 1016, -1728052956, Kind.INT_KPA, 1.0, 0.0, "Tyre.TYRE_PRESSURE_VALUE_LEFT_FRONT"),
        Fid("tirePressFR", 1016, -1728052952, Kind.INT_KPA, 1.0, 0.0, "Tyre.TYRE_PRESSURE_VALUE_RIGHT_FRONT"),
        Fid("tirePressRL", 1016, -1728052948, Kind.INT_KPA, 1.0, 0.0, "Tyre.TYRE_PRESSURE_VALUE_LEFT_REAR"),
        Fid("tirePressRR", 1016, -1728052944, Kind.INT_KPA, 1.0, 0.0, "Tyre.TYRE_PRESSURE_VALUE_RIGHT_REAR"),
        Fid("lightLow", 1004, 950009866, Kind.INT_ENUM, 1.0, 0.0, "Light.LIGHT_LOW_BEAM_LIGHT"),
        Fid("drl", 1004, 1231040528, Kind.INT_ENUM, 1.0, 0.0, "Light.LIGHT_CMD_DAY_RUNNING_LIGHT_STATE"),
        Fid("turnSignal", 1004, 950009900, Kind.INT_ENUM, 1.0, 0.0, "Light.LIGHT_TURN_SIGNAL_LIGHT"),
        Fid("maxBatTemp", 1014, 1148190752, Kind.INT_TEMP_C_OFS40, 1.0, 0.0, "Statistic.STATISTIC_HIGHEST_BATTERY_TEMP"),
        Fid("minBatTemp", 1014, 1148190736, Kind.INT_TEMP_C_OFS40, 1.0, 0.0, "Statistic.STATISTIC_LOWEST_BATTERY_TEMP"),
        Fid("powerState", 1023, 315621408, Kind.INT_ENUM, 1.0, 0.0, "Setting.SET_VEHICLE_STATE"),
        Fid("doorFL", 1001, 692060168, Kind.INT_ENUM, 1.0, 0.0, "Bodywork.BODYWORK_LEFT_HAND_FRONT_DOOR"),
        Fid("doorFR", 1001, 692060170, Kind.INT_ENUM, 1.0, 0.0, "Bodywork.BODYWORK_RIGHT_HAND_FRONT_DOOR"),
        Fid("doorRL", 1001, 692060172, Kind.INT_ENUM, 1.0, 0.0, "Bodywork.BODYWORK_LEFT_HAND_REAR_DOOR"),
        Fid("doorRR", 1001, 692060174, Kind.INT_ENUM, 1.0, 0.0, "Bodywork.BODYWORK_RIGHT_HAND_REAR_DOOR"),
        Fid("windowFL", 1001, 947912728, Kind.INT_PERCENT, 1.0, 0.0, "Bodywork.BODYWORK_WINDOW_LEFT_FRONT_PERCENT"),
        Fid("windowFR", 1001, 1267728400, Kind.INT_PERCENT, 1.0, 0.0, "Bodywork.BODYWORK_WINDOW_RIGHT_FRONT_PERCENT"),
        Fid("windowRL", 1001, 947912736, Kind.INT_PERCENT, 1.0, 0.0, "Bodywork.BODYWORK_WINDOW_LEFT_REAR_PERCENT"),
        Fid("windowRR", 1001, 1267728408, Kind.INT_PERCENT, 1.0, 0.0, "Bodywork.BODYWORK_WINDOW_RIGHT_REAR_PERCENT"),
        Fid("sunroof", 1001, 1101004808, Kind.INT_PERCENT, 1.0, 0.0, "Bodywork.BODYWORK_MOON_ROOF_OPEN_PERCENT"),
        Fid("trunk", 1001, 1074790416, Kind.INT_ENUM, 1.0, 0.0, "Bodywork.BODYWORK_BACKDOOR_CURRENT_POSITION"),
        Fid("frontTrunk", 1001, 1156579344, Kind.INT_ENUM, 1.0, 0.0, "Bodywork.BODYWORK_FRONT_HATCH_CURRENT_POSITION"),
        Fid("lockFL", 1032, 1081081864, Kind.INT_ENUM, 1.0, 0.0, "Ota.OTA_LF_DOOR_LOCK"),
        Fid("driveMode", 1006, 555745294, Kind.INT_ENUM, 1.0, 0.0, "Energy.ENERGY_OPERATION_MODE"),
        Fid("driveModeTarget", 1023, 255852712, Kind.INT_ENUM, 1.0, 0.0, "Setting.SETTING_TARGET_DRIVING_MODE"),
        Fid("workMode", 1006, 874512420, Kind.INT_ENUM, 1.0, 0.0, "Energy.ENERGY_MODE_INSTRUMENT"),
        Fid("acDefrostFront", 1000, 1077936150, Kind.INT_ENUM, 1.0, 0.0, "Ac.AC_DEFROST_FRONT_STATE"),
        Fid("acWindMode", 1000, 1077936152, Kind.INT_ENUM, 1.0, 0.0, "Ac.AC_WIND_MODE"),
        Fid("acCtrlMode", 1000, 1077936146, Kind.INT_ENUM, 1.0, 0.0, "Ac.AC_CTRL_MODE"),
        Fid("seatHeatDriver", 1000, 702545948, Kind.INT_RAW, 1.0, 0.0, "Ac.AC_MAIN_DRIVE_SEAT_HEATING_LEVEL"),
        Fid("seatVentDriver", 1000, 702545944, Kind.INT_RAW, 1.0, 0.0, "Ac.AC_MAIN_DRIVE_SEAT_VENTILATING_LEVEL"),
        Fid("seatHeatPassenger", 1000, 711983132, Kind.INT_RAW, 1.0, 0.0, "Ac.AC_PASSENGER_SEAT_HEATING_LEVEL"),
        Fid("seatVentPassenger", 1000, 711983128, Kind.INT_RAW, 1.0, 0.0, "Ac.AC_PASSENGER_SEAT_VENTILATING_LEVEL"),
        Fid("lightSide", 1004, 950009864, Kind.INT_ENUM, 1.0, 0.0, "Light.LIGHT_SIDE_LIGHT"),
        Fid("lightHigh", 1004, 950009868, Kind.INT_ENUM, 1.0, 0.0, "Light.LIGHT_HIGH_BEAM_LIGHT"),
        Fid("seatbeltFR", 1007, 638582811, Kind.INT_ENUM, 1.0, 0.0, "Instrument.INSTRUMENT_DD_DEPUTY_SAFETYBELT_STATE"),
        Fid("seatbeltRL", 1007, 638582812, Kind.INT_ENUM, 1.0, 0.0, "Instrument.INSTRUMENT_DD_REAR_LEFT_SAFETYBELT_STATE"),
        Fid("seatbeltRM", 1007, 638582813, Kind.INT_ENUM, 1.0, 0.0, "Instrument.INSTRUMENT_DD_REAR_MID_SAFETYBELT_STATE"),
        Fid("seatbeltRR", 1007, 638582814, Kind.INT_ENUM, 1.0, 0.0, "Instrument.INSTRUMENT_DD_REAR_RIGHT_SAFETYBELT_STATE"),
        Fid("rearBeltSettingRL", 1023, 824180746, Kind.INT_RAW, 1.0, 0.0, "Setting.SETTING_LEFT_REAR_ROW_SAFETYBELT_STATUS"),
        Fid("rearBeltSettingRM", 1023, 824180750, Kind.INT_RAW, 1.0, 0.0, "Setting.SETTING_CENTER_REAR_ROW_SAFETYBELT_STATUS"),
        Fid("rearBeltSettingRR", 1023, 824180748, Kind.INT_RAW, 1.0, 0.0, "Setting.SETTING_RIGHT_REAR_ROW_SAFETYBELT_STATUS"),
        Fid("occupancyFL", 1042, 824180800, Kind.INT_ENUM, 1.0, 0.0, "Safety.SAFETY_BELT_PASSENGER_COMMAND_FRONT_ROW_SEAT_LEFT"),
        Fid("occupancyFR", 1042, 824180758, Kind.INT_ENUM, 1.0, 0.0, "Safety.SAFETY_BELT_PASSENGER_COMMAND_FRONT_ROW_SEAT_RIGHT"),
        Fid("occupancyRL", 1042, 824180760, Kind.INT_ENUM, 1.0, 0.0, "Safety.SAFETY_BELT_PASSENGER_COMMAND_SECOND_ROW_SEAT_LEFT"),
        Fid("occupancyRM", 1042, 824180762, Kind.INT_ENUM, 1.0, 0.0, "Safety.SAFETY_BELT_PASSENGER_COMMAND_SECOND_ROW_SEAT_MID"),
        Fid("occupancyRR", 1042, 824180764, Kind.INT_ENUM, 1.0, 0.0, "Safety.SAFETY_BELT_PASSENGER_COMMAND_SECOND_ROW_SEAT_RIGHT"),
        Fid("lightLevel", 1043, 315621396, Kind.INT_RAW, 1.0, 0.0, "Sensor.SENSOR_LIGHT"),
        Fid("keyBatteryStatus", 1014, 402653200, Kind.INT_ENUM, 1.0, 0.0, "Statistic.STATISTIC_KEY_BATTERY_LEVEL"),
        Fid("wiperRelay", 1046, 1336934438, Kind.INT_ENUM, 1.0, 0.0, "Wiper.WIPER_RELAY_STATE"),
        Fid("autoWipers", 1046, 321912862, Kind.INT_ENUM, 1.0, 0.0, "Setting.SET_AUTO_RAIN_WIPER_SWITCH"),
        Fid("insulationKohm", 1039, 1134559256, Kind.INT_RAW, 1.0, 0.0, "Gb.GB_BMC_INSULATION_VALUE"),
        Fid("motorTempFront", 1039, 1154482192, Kind.INT_RAW, 1.0, 0.0, "Gb.GB_FRONT_MOTOR_TEMP"),
        Fid("motorTempRear", 1039, 1155530768, Kind.INT_RAW, 1.0, 0.0, "Gb.GB_TREAR_MOTOR_TEMP"),
        Fid("inverterTempFront", 1039, 1154482184, Kind.INT_RAW, 1.0, 0.0, "Gb.GB_FRONT_MOTOR_IPM_TEMP"),
        Fid("inverterTempRear", 1039, 1155530760, Kind.INT_RAW, 1.0, 0.0, "Gb.GB_REAR_MOTOR_IPM_TEMP"),
        Fid("hvVoltage", 1009, 1145045000, Kind.INT_RAW, 1.0, 0.0, "Charging.CHARGING_CHARGE_BATTERY_VOLT"),
        Fid("hvCurrent", 1009, 1145045016, Kind.FLOAT_AMP, 1.0, 0.0, "Charging.CHARGING_CHARGE_CURRENT"),
        Fid("motorCurrentFront", 1009, 1186988040, Kind.FLOAT_AMP, 1.0, 0.0, "Charging.CHARGING_DRIVER_MOTOR_CURRENT"),
        Fid("motorCurrentRear", 1009, 1186988056, Kind.FLOAT_AMP, 1.0, 0.0, "Charging.CHARGING_REAR_DRIVER_MOTOR_CURRENT"),
        Fid("bmsMaxChargeKw", 1014, 877658136, Kind.INT_SCALED, 0.1, 0.0, "Statistic.STATISTIC_MAX_CHARGE_POWER_ALLOW"),
        Fid("bmsMaxDischargeAllowKw", 1014, 877658120, Kind.INT_SCALED, 0.1, 0.0, "Statistic.STATISTIC_MAX_DISCHARGE_POWER_ALLOW"),
        Fid("bmsMaxDischargeKw", 1014, 1145045048, Kind.INT_RAW, 1.0, 0.0, "Statistic.STATISTIC_BATTERY_AVAILABLE_POWER"),
        Fid("motorRpmFront", 1012, 1141899272, Kind.INT_RAW, 1.0, 0.0, "Engine.ENGINE_FRONT_MOTOR_SPEED"),
        Fid("motorRpmRear", 1012, 621805576, Kind.INT_RAW, 1.0, 0.0, "Engine.ENGINE_REAR_MOTOR_SPEED"),
        Fid("compressorW", 1000, 1031798840, Kind.INT_RAW, 1.0, 0.0, "Power.POWER_COMPRESSOR_CONSUME_POWER"),
        Fid("tyreTempFL", 1007, 1246797848, Kind.INT_RAW, 1.0, 0.0, "Instrument.INSTRUMENT_2IN1_LF_TYRE_TEMPERATURE"),
        Fid("tyreTempFR", 1007, 1246797860, Kind.INT_RAW, 1.0, 0.0, "Instrument.INSTRUMENT_2IN1_RF_TYRE_TEMPERATURE"),
        Fid("tyreTempRL", 1007, 1246797872, Kind.INT_RAW, 1.0, 0.0, "Instrument.INSTRUMENT_2IN1_LB_TYRE_TEMPERATURE"),
        Fid("tyreTempRR", 1007, 1246797884, Kind.INT_RAW, 1.0, 0.0, "Instrument.INSTRUMENT_2IN1_RB_TYRE_TEMPERATURE"),
        Fid("pedalAccel", 1013, 874512392, Kind.INT_RAW, 1.0, 0.0, "Speed.SPEED_ACCELERATOR_S"),
        Fid("pedalBrake", 1013, 874512400, Kind.INT_RAW, 1.0, 0.0, "Speed.SPEED_BRAKE_S"),
        Fid("chargerConnectState", 1009, 89128973, Kind.INT_RAW, 1.0, 0.0, "Charging.CHARGING_CHARGER_CONNECT_STATE"),
        Fid("chargeConnectIndicator", 1007, 710934572, Kind.INT_RAW, 1.0, 0.0, "Instrument.INSTRUMENT_2IN1_FAULT_POWER_BATTERY_CHARGE_CONNECT_INDICATOR"),
        Fid("soh", 1014, 1145045032, Kind.INT_PERCENT, 1.0, 0.0, "Statistic.STATISTIC_BATTERY_HEALTHY_INDEX"),
        Fid("lifetimeAvgPhm", 1014, 1246761008, Kind.FLOAT_KWH, 1.0, 0.0, "Statistic.STATISTIC_TOTAL_ELEC_CON_PHM"),
        Fid("chargingType", 1009, 876609592, Kind.INT_ENUM, 1.0, 0.0, "Charging.CHARGING_TYPE"),
        Fid("batteryType", 1009, -1728053169, Kind.INT_ENUM, 1.0, 0.0, "Charging.CHARGING_BATTERY_TYPE"),
        Fid("chargingCapacity", 1009, 666894360, Kind.FLOAT_KWH, 1.0, 0.0, "Charging.CHARGING_CAPACITY"),
        Fid("bsdLeft", 1038, 1098907664, Kind.INT_ENUM, 1.0, 0.0, "Adas.ADAS_RIGHT_RADAR_LCA_WARNINGLEFT"),
        Fid("bsdRight", 1038, 1098907666, Kind.INT_ENUM, 1.0, 0.0, "Adas.ADAS_RIGHT_RADAR_LCA_WARNINGRIGHT"),
        Fid("seatVentStatusDriver", 1000, 702545928, Kind.INT_RAW, 1.0, 0.0, "Ac.AC_MAIN_DRIVE_SEAT_VENTILATING_STATUS"),
        Fid("seatHeatStatusDriver", 1000, 702545932, Kind.INT_RAW, 1.0, 0.0, "Ac.AC_MAIN_DRIVE_SEAT_HEATING_STATUS"),
        Fid("seatConfigVentLf", 1000, 715132952, Kind.INT_RAW, 1.0, 0.0, "Ac.AC_LF_SEAT_VENTILATION_CONFIG"),
        Fid("seatConfigHeatLf", 1000, 715132955, Kind.INT_RAW, 1.0, 0.0, "Ac.AC_LF_SEAT_HEATING_CONFIG"),
        Fid("seatHasDriverVent", 1023, -811597816, Kind.INT_RAW, 1.0, 0.0, "Setting.SET_HAS_DRIVER_SEAT_VENTILATING"),
        Fid("seatHasDriverHeat", 1023, -811597813, Kind.INT_RAW, 1.0, 0.0, "Setting.SET_HAS_DRIVER_SEAT_HEATING"),
        Fid("seatCandPassengerVentStatus", 1000, 711983112, Kind.INT_RAW, 1.0, 0.0, "Ac.AC_PASSENGER_SEAT_VENTILATING_STATUS"),
        Fid("seatCandPassengerHeatStatus", 1000, 711983116, Kind.INT_RAW, 1.0, 0.0, "Ac.AC_PASSENGER_SEAT_HEATING_STATUS"),
        Fid("seatCand3ceVentStatusDriver", 1000, 1021313032, Kind.INT_RAW, 1.0, 0.0, "Ac.AC_MAIN_DRIVE_SEAT_VENTILATING_STATUS_3CE_hal_only"),
        Fid("seatCand3ceHeatStatusDriver", 1000, 1021313034, Kind.INT_RAW, 1.0, 0.0, "Ac.AC_MAIN_DRIVE_SEAT_HEATING_STATUS_3CE_hal_only"),
        Fid("seatCand3ceVentLevelDriver", 1000, 1021313044, Kind.INT_RAW, 1.0, 0.0, "Ac.AC_MAIN_DRIVE_SEAT_VENTILATING_LEVEL_3CE_hal_only"),
        Fid("seatCand3ceHeatLevelDriver", 1000, 1021313048, Kind.INT_RAW, 1.0, 0.0, "Ac.AC_MAIN_DRIVE_SEAT_HEATING_LEVEL_3CE_hal_only"),
        Fid("seatCandLrseVentStatusRl", 1000, 412180522, Kind.INT_RAW, 1.0, 0.0, "Ac.AC_LRSE_REAR_LEFT_SEAT_VENTILATING_STATUS_SET_hal_only"),
        Fid("seatCandLrseHeatStatusRl", 1000, 412180526, Kind.INT_RAW, 1.0, 0.0, "Ac.AC_LRSE_REAR_LEFT_SEAT_HEATING_STATUS_SET_hal_only"),
        Fid("seatCandLrseVentStatusRr", 1000, 412180528, Kind.INT_RAW, 1.0, 0.0, "Ac.AC_LRSE_REAR_RIGHT_SEAT_VENTILATING_STATUS_SET_hal_only"),
        Fid("seatCandLrseHeatStatusRr", 1000, 412180532, Kind.INT_RAW, 1.0, 0.0, "Ac.AC_LRSE_REAR_RIGHT_SEAT_HEATING_STATUS_SET_hal_only"),
        Fid("seatCandLrseLevel1", 1000, 412180536, Kind.INT_RAW, 1.0, 0.0, "Ac.AC_LRSE_REAR_LEFT_SEAT_VENTILATING_LEVEL_SET_hal_only"),
        Fid("seatCandLrseLevel2", 1000, 412180540, Kind.INT_RAW, 1.0, 0.0, "Ac.AC_LRSE_REAR_LEFT_SEAT_HEATING_LEVEL_SET_hal_only"),
        Fid("seatCandLrseLevel3", 1000, 412180544, Kind.INT_RAW, 1.0, 0.0, "Ac.AC_LRSE_REAR_RIGHT_SEAT_VENTILATING_LEVEL_SET_hal_only"),
        Fid("seatCandLrseLevel4", 1000, 412180548, Kind.INT_RAW, 1.0, 0.0, "Ac.AC_LRSE_REAR_RIGHT_SEAT_HEATING_LEVEL_SET_hal_only"),
        Fid("fuelPercent", 1014, 1246785600, Kind.INT_PERCENT, 1.0, 0.0, "Statistic.STATISTIC_FUEL_PERCENTAGE"),
        Fid("fuelRangeKm", 1014, 1246773304, Kind.INT_RANGE_KM, 1.0, 0.0, "Statistic.STATISTIC_FUEL_DRIVING_RANGE"),
        Fid("fuelTotalL", 1014, 1246760976, Kind.FLOAT_LITERS, 1.0, 0.0, "Statistic.STATISTIC_TOTAL_FUEL_CONSUMPTION"),
        Fid("mileageHevKm", 1014, 1246773264, Kind.INT_RAW, 1.0, 0.0, "Statistic.STATISTIC_MILEAGE_HEV"),
        Fid("mileageEvKm", 1014, 1246773284, Kind.INT_RAW, 1.0, 0.0, "Statistic.STATISTIC_MILEAGE_EV"),
        Fid("engineRpm", 1012, 339738642, Kind.INT_RPM, 1.0, 0.0, "Engine.ENGINE_SPEED"),
        Fid("evRangeKm", 1014, 1246765118, Kind.INT_RANGE_KM, 1.0, 0.0, "Statistic.STATISTIC_ELEC_DRIVING_RANGE"),
        // 1.4.119：综合油耗（50km 等效，仪表口径）直读 —— 原来是事件推送 1246785586 才更新，
        // 10 小时才 1 条；进 fid 表后每次轮询都拿车机值，开机即有数，且**不需要任何显示偏置**。
        Fid("fuelConsumption", 1014, 1246785586, Kind.INT_SCALED, 0.1, 0.0, "Statistic.STATISTIC_LAST_50KM_EQUAL_FUEL_CON"),
        // 1.4.135：云辇C（智能悬架）模式 —— 实车 00:34–00:36 定标，切换瞬间就变：
        //   舒适 = 1，运动 = 2；全程只出现这两个值。
        // 同表的 SET_HAS_DYNAMIC_SUSPENSION(-1209008120) 读数与它逐样本完全一致
        // （名字带 HAS 但实际跟着模式走），因此不单独收录，避免同址歧义。
        Fid("suspensionMode", 1023, 938475528, Kind.INT_ENUM, 1.0, 0.0, "Setting.SET_DYNAMIC_SUSPENSION_ACTUAL_OPERATING_MODE"),
    )

    val byName: Map<String, Fid> = all.associateBy { it.name }
}
