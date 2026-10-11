package app.galla.captain

import androidx.camera.core.Preview

/**
 * Hands the on-screen preview (Kavach screen) to whichever camera user is active:
 * the recorder service while recording, or the screen's own "camera check" otherwise.
 * All calls happen on the main thread.
 */
object PreviewHub {
    /** Preview use case bound by the recorder, while it records video. */
    var servicePreview: Preview? = null
    /** Surface of the preview box on the Kavach screen, while it is visible. */
    var surface: Preview.SurfaceProvider? = null
    /** Called when the recorder binds or releases the camera, so the screen can switch over. */
    var onCameraChanged: (() -> Unit)? = null

    fun attach(sp: Preview.SurfaceProvider) { surface = sp; servicePreview?.setSurfaceProvider(sp) }
    fun detach() { surface = null; servicePreview?.setSurfaceProvider(null) }
}
