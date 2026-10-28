package dev.drone.controller.mavlink

// Official common.xml at mavlink/mavlink commit 9324a919111e64449ee2e49b839f75730e733da3.
// Independent pymavlink-generated 43-byte SYS_STATUS with three extension masks.
// Regenerate with reference_current_sys_status.py; legacy 31-byte vector remains in GoldenVectors.
internal object CurrentVectors {
    const val RX_SYS_STATUS_43 = "fd2b0000112a01010000010000800700000005000000fa005e2e85ff0a0002000300040005000600400100008040302010674523f1ac3e"
}
