package com.focuskiosk

import com.focuskiosk.ui.SetupWizardActivity

/**
 * MainActivity
 * ────────────
 * Primary user-facing launcher activity for Focus Kiosk.
 * Inherits the complete modern Apple-style Setup Wizard, lock countdown,
 * and work window configuration from SetupWizardActivity.
 *
 * Registered as the sole android.intent.category.LAUNCHER activity in AndroidManifest.xml,
 * guaranteeing permanent launcher visibility across all Android versions and OEM skins.
 */
class MainActivity : SetupWizardActivity()
