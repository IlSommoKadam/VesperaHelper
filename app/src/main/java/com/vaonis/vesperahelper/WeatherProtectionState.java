package com.vaonis.vesperahelper;

/**
 * State machine for the Automatic Weather Protection sequence.
 *
 * <pre>
 * IDLE → CHECKING_WEATHER
 *   ├── No rain → IDLE
 *   └── Rain → RAIN_DETECTED → STOPPING_SESSION → CLOSING_VESPERA
 *              → WAITING_FOR_ARM_EVENT → VERIFYING_CLOSED_STATE
 *              → POWERING_OFF → PROTECTION_COMPLETED
 * Any failure → ERROR
 * </pre>
 */
enum WeatherProtectionState {
    IDLE,
    CHECKING_WEATHER,
    RAIN_DETECTED,
    STOPPING_SESSION,
    CLOSING_VESPERA,
    WAITING_FOR_ARM_EVENT,
    VERIFYING_CLOSED_STATE,
    POWERING_OFF,
    PROTECTION_COMPLETED,
    ERROR
}
