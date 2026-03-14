package com.jellyfinbroadcast.phone

import com.journeyapps.barcodescanner.CaptureActivity

/**
 * CaptureActivity subclass that allows the manifest to control orientation
 * instead of ZXing forcing landscape.
 */
class PortraitCaptureActivity : CaptureActivity()
