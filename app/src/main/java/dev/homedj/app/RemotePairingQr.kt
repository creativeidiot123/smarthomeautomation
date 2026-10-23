package dev.homedj.app

import androidx.activity.ComponentActivity
import com.google.mlkit.vision.barcode.common.Barcode
import com.google.mlkit.vision.codescanner.GmsBarcodeScannerOptions
import com.google.mlkit.vision.codescanner.GmsBarcodeScanning
import com.google.zxing.BarcodeFormat
import com.google.zxing.common.BitMatrix
import com.google.zxing.qrcode.QRCodeWriter

/** Activity edge owns scanner UI; completion retains only the ViewModel, including across rotation. */
internal fun scanRemotePairingQr(activity: ComponentActivity, model: RemotePairingViewModel) {
    val version = model.beginScan() ?: return
    val options = GmsBarcodeScannerOptions.Builder().setBarcodeFormats(Barcode.FORMAT_QR_CODE)
        .enableAutoZoom().build()
    try {
        GmsBarcodeScanning.getClient(activity, options).startScan()
            .addOnSuccessListener { model.scanned(version, it.rawValue) }
            .addOnCanceledListener { model.scanCancelled(version) }
            .addOnFailureListener { model.scanFailed(version, it) }
    } catch (failure: Exception) {
        model.scanFailed(version, failure)
    }
}

internal fun pairingQrMatrix(payload: String): BitMatrix =
    QRCodeWriter().encode(payload, BarcodeFormat.QR_CODE, 0, 0)
