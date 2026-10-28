package dev.drone.controller.mavlink

/** Independent pymavlink2.4.49 ardupilotmega-v2 fixtures. */
internal object DialectVectors {
    const val TX_OVERRIDE_1470="fd12000000ffbe460000be05dc054c04dc05ffffffffffffffff01014250"
    const val RX_VFR_HUD="fd130000652a014a00000000c03f0000204000002a42000080be7b0004cc38"
    const val RX_SENSOR_OFFSETS="fd290000662a019600000000003ecd8b0100c4090000cdcccc3dcdcc4c3e9a99993ecdcc8c3f9a99993f6666a63f0100feff0381e1"
    const val RX_QUATERNION="fd200000672a011f000040e201000000803f000000000000000000000000cdcccc3dcdcc4c3e9a99993e9603"
    const val RX_AHRS="fd1c0000682a01a30000cdcccc3dcdcc4c3e9a99993ecdcccc3e0000003f9a99193f3333333feefa"
    const val RX_RC_CHANNELS_RAW="fd160000692a0123000040e20100be05dc054c04dc05ffffffffffffffff00ff7c5c"
}
