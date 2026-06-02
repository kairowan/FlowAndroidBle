package com.flowble.internal

import com.flowble.NotificationSetupMode
import com.flowble.model.CharacteristicObservationMode
import com.flowble.model.CharacteristicProperty
import java.util.UUID

internal data class ObservationSetup(
    val kind: ObservationKind,
    val enableValue: ByteArray,
    val cccdWriteMode: ObservationCccdWriteMode
) {
    val label: String get() = kind.label
}

internal enum class ObservationKind(val label: String) {
    NOTIFICATION("notification"),
    INDICATION("indication")
}

internal enum class ObservationCccdWriteMode {
    BEFORE_EMIT,
    AFTER_EMIT,
    SKIP
}

internal fun resolveObservationSetup(
    characteristicUuid: UUID,
    properties: Set<CharacteristicProperty>,
    mode: CharacteristicObservationMode,
    setupMode: NotificationSetupMode = NotificationSetupMode.DEFAULT
): ObservationSetup {
    val supportsNotify = CharacteristicProperty.NOTIFY in properties
    val supportsIndicate = CharacteristicProperty.INDICATE in properties
    val cccdWriteMode = when (setupMode) {
        NotificationSetupMode.DEFAULT -> ObservationCccdWriteMode.BEFORE_EMIT
        NotificationSetupMode.QUICK_SETUP -> ObservationCccdWriteMode.AFTER_EMIT
        NotificationSetupMode.COMPAT -> ObservationCccdWriteMode.SKIP
    }

    return when (mode) {
        CharacteristicObservationMode.AUTO -> when {
            supportsNotify -> ObservationSetup(
                kind = ObservationKind.NOTIFICATION,
                enableValue = ENABLE_NOTIFICATION_VALUE,
                cccdWriteMode = cccdWriteMode
            )
            supportsIndicate -> ObservationSetup(
                kind = ObservationKind.INDICATION,
                enableValue = ENABLE_INDICATION_VALUE,
                cccdWriteMode = cccdWriteMode
            )
            else -> throw IllegalArgumentException(
                "Characteristic $characteristicUuid does not support notifications or indications"
            )
        }

        CharacteristicObservationMode.NOTIFICATION -> {
            if (!supportsNotify) {
                throw IllegalArgumentException(
                    "Characteristic $characteristicUuid does not support notifications"
                )
            }
            ObservationSetup(
                kind = ObservationKind.NOTIFICATION,
                enableValue = ENABLE_NOTIFICATION_VALUE,
                cccdWriteMode = cccdWriteMode
            )
        }

        CharacteristicObservationMode.INDICATION -> {
            if (!supportsIndicate) {
                throw IllegalArgumentException(
                    "Characteristic $characteristicUuid does not support indications"
                )
            }
            ObservationSetup(
                kind = ObservationKind.INDICATION,
                enableValue = ENABLE_INDICATION_VALUE,
                cccdWriteMode = cccdWriteMode
            )
        }
    }
}
