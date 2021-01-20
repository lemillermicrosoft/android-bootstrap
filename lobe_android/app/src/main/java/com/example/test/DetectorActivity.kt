package com.example.test

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Matrix
import android.hardware.Camera
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraManager
import android.media.ImageReader
import android.media.MediaActionSound
import android.os.Build
import android.os.SystemClock
import android.util.Base64
import android.util.Size
import android.util.TypedValue
import android.widget.Toast
import androidx.annotation.RequiresApi
import com.android.volley.Request
import com.android.volley.Response
import com.android.volley.toolbox.StringRequest
import com.example.test.api.LobeApiHelper.Companion.postPrediction
import com.example.test.customview.OverlayView
import com.example.test.env.ImageUtils
import com.example.test.env.Logger
import com.example.test.tflite.Classifier
import com.example.test.tracking.MultiBoxTracker
import com.github.kittinunf.fuel.Fuel
import com.github.kittinunf.fuel.core.BlobDataPart
import com.github.kittinunf.fuel.core.DataPart
import com.github.kittinunf.fuel.core.InlineDataPart
import com.github.kittinunf.fuel.core.Method
import org.json.JSONArray
import org.json.JSONObject
import java.io.*
import java.net.HttpURLConnection
import java.net.URL
import java.time.Instant

@RequiresApi(Build.VERSION_CODES.KITKAT)
class DetectorActivity : CameraActivity(), ImageReader.OnImageAvailableListener {

    private val LOGGER: Logger = Logger()

    // Configuration values for the prepackaged SSD model.
    private val TF_OD_API_INPUT_SIZE = 448 // 448

    // Minimum detection confidence to track a detection.
    private val MAINTAIN_ASPECT = false

    @RequiresApi(Build.VERSION_CODES.LOLLIPOP)
    private val DESIRED_PREVIEW_SIZE = Size(1280, 960)
    private val SAVE_PREVIEW_BITMAP = false
    private val TEXT_SIZE_DIP = 10f
    var trackingOverlay: OverlayView? = null
    private var sensorOrientation: Int? = null
    private var detector: Classifier? = null
    private var lastProcessingTimeMs: Long = 0
    private var croppedBitmap: Bitmap? = null
    private var cropCopyBitmap: Bitmap? = null
    private var computingDetection = false
    private var timestamp: Long = 0
    private var frameToCropTransform: Matrix? = null
    private var cropToFrameTransform: Matrix? = null
    private var tracker: MultiBoxTracker? = null

    private var imageSizeX = 0

    /** Input image size of the model along y axis.  */
    private var imageSizeY = 0


    private fun recreateClassifier(
        model: Classifier.Model,
        device: Classifier.Device,
        numThreads: Int
    ) {
        if (detector != null) {
            LOGGER.d("Closing classifier.")
            detector!!.close()
            detector = null
        }
        if (device === Classifier.Device.GPU
            && (model === Classifier.Model.QUANTIZED_MOBILENET || model === Classifier.Model.QUANTIZED_EFFICIENTNET)
        ) {
            LOGGER.d("Not creating classifier: GPU doesn't support quantized models.")
//            runOnUiThread {
//                Toast.makeText(this, R.string.tfe_ic_gpu_quant_error, Toast.LENGTH_LONG).show()
//            }
            return
        }
        try {
            LOGGER.d(
                "Creating classifier (model=%s, device=%s, numThreads=%d)",
                model,
                device,
                numThreads
            )
            detector = Classifier.create(this, model, device, numThreads)
        } catch (e: IOException) {
            LOGGER.e(
                e,
                "Failed to create classifier."
            )
        }

        // Updates the input image size.
        imageSizeX = detector!!.getImageSizeX()
        imageSizeY = detector!!.getImageSizeY()
    }


    @RequiresApi(Build.VERSION_CODES.LOLLIPOP)
    override fun onPreviewSizeChosen(size: Size?, rotation: Int) {
        val textSizePx = TypedValue.applyDimension(
            TypedValue.COMPLEX_UNIT_DIP,
            TEXT_SIZE_DIP,
            resources.displayMetrics
        )
        tracker = MultiBoxTracker(this)
        var cropSize: Int = TF_OD_API_INPUT_SIZE
        try {
            recreateClassifier(getModel()!!, getDevice()!!, getNumThreads())
            cropSize = TF_OD_API_INPUT_SIZE
        } catch (e: IOException) {
            e.printStackTrace()
            LOGGER.e(e, "Exception initializing classifier!")
            val toast = Toast.makeText(
                applicationContext, "Classifier could not be initialized", Toast.LENGTH_SHORT
            )
            toast.show()
            finish()
        }

        previewWidth = size!!.width
        previewHeight = size!!.height

        sensorOrientation = rotation - getScreenOrientation()
        LOGGER.i(
            "Camera orientation relative to screen canvas: %d",
            sensorOrientation
        )

        LOGGER.i("Initializing at size %dx%d", previewWidth, previewHeight)
        rgbFrameBitmap = Bitmap.createBitmap(previewWidth, previewHeight, Bitmap.Config.ARGB_8888)
        croppedBitmap = Bitmap.createBitmap(cropSize, cropSize, Bitmap.Config.ARGB_8888)

        frameToCropTransform = ImageUtils.getTransformationMatrix(
            previewWidth, previewHeight,
            cropSize, cropSize,
            sensorOrientation!!, MAINTAIN_ASPECT
        )

        cropToFrameTransform = Matrix()
        frameToCropTransform!!.invert(cropToFrameTransform)

        trackingOverlay = findViewById(R.id.tracking_overlay) as OverlayView
        trackingOverlay!!.addCallback(
            object : OverlayView.DrawCallback {
                override fun drawCallback(canvas: Canvas?) {
                    tracker!!.draw(canvas)
                    if (isDebug()) {
                        tracker!!.drawDebug(canvas)
                    }
                }
            })
        tracker!!.setFrameConfiguration(previewWidth, previewHeight, sensorOrientation!!)
    }

    @RequiresApi(Build.VERSION_CODES.N)
    override fun processImage() {
        ++timestamp
        val currTimestamp = timestamp
        trackingOverlay!!.postInvalidate()
        // No mutex needed as this method is not reentrant.
        if (computingDetection) {
            readyForNextImage()
            return
        }
        computingDetection = true
//    LOGGER.i("Preparing image " + currTimestamp + " for detection in bg thread.");
        if (useImage) {
            val cur = BitmapFactory.decodeByteArray(inputData, 0, inputData!!.size, null)
            rgbFrameBitmap = cur.copy(Bitmap.Config.ARGB_8888, true)
        } else {
            rgbFrameBitmap =
                Bitmap.createBitmap(previewWidth, previewHeight, Bitmap.Config.ARGB_8888)
            rgbFrameBitmap!!.setPixels(
                getRgbBytes(),
                0,
                previewWidth,
                0,
                0,
                previewWidth,
                previewHeight
            )
        }
        readyForNextImage()
        // For examining the actual TF input.
        if (SAVE_PREVIEW_BITMAP) {
            ImageUtils.saveBitmap(croppedBitmap)
        }

        runInBackground(
            Runnable {
                //            LOGGER.i("Running detection on image " + currTimestamp);
                var rawBitmap: Bitmap? = null
                if (!useImage) {
                    val matrix = Matrix()
                    matrix.postRotate(90f)
                    var targetWidth =
                        previewWidth!!.toFloat() / previewHeight!!.toFloat() * screenHeight!!.toFloat()
                    val scaledBitmap =
                        Bitmap.createScaledBitmap(
                            rgbFrameBitmap!!,
                            targetWidth!!.toInt(),
                            screenHeight!!,
                            true
                        )
                    val rotatedBitmap = Bitmap.createBitmap(
                        scaledBitmap,
                        0,
                        0,
                        scaledBitmap.width,
                        scaledBitmap.height,
                        matrix,
                        true
                    )

                    var w =
                        screenWidth!!.toFloat() / screenHeight!!.toFloat() * rotatedBitmap.height
                    rawBitmap =
                        Bitmap.createBitmap(rotatedBitmap, 0, 0, w.toInt(), rotatedBitmap.height)
                } else {
                    rawBitmap = rgbFrameBitmap
                }

//                val matrix = Matrix()
//                matrix.postRotate(90f)
                var curWidth = rawBitmap!!.width
                var curHeight = rawBitmap!!.height
                var squareBitmap: Bitmap? = null
                if (curHeight > curWidth) {
                    squareBitmap = Bitmap.createBitmap(
                        rawBitmap,
                        0,
                        ((curHeight.toFloat() - curWidth.toFloat()) / 2.toFloat()).toInt(),
                        curWidth,
                        curWidth
                    )
                } else {
                    squareBitmap = Bitmap.createBitmap(
                        rawBitmap,
                        ((curWidth.toFloat() - curHeight.toFloat()) / 2.toFloat()).toInt(),
                        0,
                        curHeight,
                        curHeight
                    )
                }

                val rotation = if (this.getIsFrontFacing()) 180 else 0 - getScreenOrientation()

                val canvas1 = Canvas(croppedBitmap!!)
                val trans = ImageUtils.getTransformationMatrix(
                    squareBitmap!!.width, squareBitmap!!.height,
                    croppedBitmap!!.width, croppedBitmap!!.height,
                    rotation, MAINTAIN_ASPECT
                )

                canvas1.drawBitmap(squareBitmap!!, trans, null)
                val startTime = SystemClock.uptimeMillis()
                // hit API instead
                val results: List<Classifier.Recognition> =
                    detector!!.recognizeImage(croppedBitmap, sensorOrientation!!)

                val bitmap = croppedBitmap
                val stream = ByteArrayOutputStream()
                bitmap!!.compress(Bitmap.CompressFormat.JPEG, 90, stream)
                val image = stream.toByteArray()
                imageToUpload = image

                val str = Base64.encodeToString(image, Base64.NO_WRAP)
                if (!skipPrediction) {
                    postPrediction(this, str, { predictedLabel, confidence ->
                        if (!(label!! as EditTextBackEvent).modifyingLabel && !skipPrediction) {
                            label!!.text = predictedLabel
                            progressBar!!.setProgress(confidence, true)
                        }
                    })
                }


//                lastProcessingTimeMs = SystemClock.uptimeMillis() - startTime
//                cropCopyBitmap = Bitmap.createBitmap(croppedBitmap!!)
//                val canvas = Canvas(cropCopyBitmap!!)
//                val paint = Paint()
//                paint.color = Color.RED
//                paint.style = Paint.Style.STROKE
//                paint.strokeWidth = 2.0f
//                var minimumConfidence: Float =
//                    MINIMUM_CONFIDENCE_TF_OD_API
//                when (MODE) {
//                    DetectorMode.TF_OD_API -> minimumConfidence =
//                        MINIMUM_CONFIDENCE_TF_OD_API
//                }
//                val mappedRecognitions: MutableList<Classifier.Recognition> =
//                    LinkedList<Classifier.Recognition>()
//                for (result in results) {
//                    val location: RectF = result.getLocation()
//                    if (location != null && result.getConfidence() >= minimumConfidence) {
//                        //                canvas.drawRect(location, paint);
//                        cropToFrameTransform!!.mapRect(location)
//                        result.setLocation(location)
//                        mappedRecognitions.add(result)
//                    }
//                }
//                tracker!!.trackResults(mappedRecognitions, currTimestamp)
//                trackingOverlay!!.postInvalidate()
                computingDetection = false
            })
    }

    override fun getLayoutId(): Int {
        return R.layout.tfe_od_camera_connection_fragment_tracking
    }

    override fun getDesiredPreviewFrameSize(): Size {
        return DESIRED_PREVIEW_SIZE
    }

    private enum class DetectorMode {
        TF_OD_API
    }
}


class Multipart
/**
 * This constructor initializes a new HTTP POST request with content type
 * is set to multipart/form-data
 * @param url
 * *
 * @throws IOException
 */
@Throws(IOException::class)
constructor(url: URL) {

    companion object {
        private val LINE_FEED = "\r\n"
        private val maxBufferSize = 1024 * 1024
        private val charset = "UTF-8"
    }

    // creates a unique boundary based on time stamp
    private val boundary: String = "===" + System.currentTimeMillis() + "==="
    private val httpConnection: HttpURLConnection = url.openConnection() as HttpURLConnection
    private val outputStream: OutputStream
    private val writer: PrintWriter


    init {

        httpConnection.setRequestProperty("Accept-Charset", "UTF-8")
        httpConnection.setRequestProperty("Connection", "Keep-Alive")
        httpConnection.setRequestProperty("Cache-Control", "no-cache")
        httpConnection.setRequestProperty(
            "Content-Type",
            "multipart/form-data; boundary=" + boundary
        )
        httpConnection.setChunkedStreamingMode(maxBufferSize)
        httpConnection.doInput = true
        httpConnection.doOutput = true    // indicates POST method
        httpConnection.useCaches = false
        outputStream = httpConnection.outputStream
        writer = PrintWriter(OutputStreamWriter(outputStream, charset), true)
    }

    /**
     * Adds a form field to the request
     * @param name  field name
     * *
     * @param value field value
     */
    fun addFormField(name: String, value: String) {
        writer.append("--").append(boundary).append(LINE_FEED)
        writer.append("Content-Disposition: form-data; name=\"").append(name).append("\"")
            .append(LINE_FEED)
        writer.append(LINE_FEED)
        writer.append(value).append(LINE_FEED)
        writer.flush()
    }

    /**
     * Adds a upload file section to the request
     * @param fieldName  - name attribute in <input type="file" name="..."></input>
     * *
     * @param uploadFile - a File to be uploaded
     * *
     * @throws IOException
     */
    @Throws(IOException::class)
    fun addFilePart(fieldName: String, uploadFile: File, fileName: String, fileType: String) {
        writer.append("--").append(boundary).append(LINE_FEED)
        writer.append("Content-Disposition: file; name=\"").append(fieldName)
            .append("\"; filename=\"").append(fileName).append("\"").append(LINE_FEED)
        writer.append("Content-Type: ").append(fileType).append(LINE_FEED)
        writer.append(LINE_FEED)
        writer.flush()

        val inputStream = FileInputStream(uploadFile)
        inputStream.copyTo(outputStream, maxBufferSize)

        outputStream.flush()
        inputStream.close()
        writer.append(LINE_FEED)
        writer.flush()
    }

    /**
     * Adds a header field to the request.
     * @param name  - name of the header field
     * *
     * @param value - value of the header field
     */
    fun addHeaderField(name: String, value: String) {
        writer.append(name + ": " + value).append(LINE_FEED)
        writer.flush()
    }

    /**
     * Upload the file and receive a response from the server.
     * @param onFileUploadedListener
     * *
     * @throws IOException
     */
    @Throws(IOException::class)
    fun upload(onFileUploadedListener: OnFileUploadedListener?) {
        writer.append(LINE_FEED).flush()
        writer.append("--").append(boundary).append("--")
            .append(LINE_FEED)
        writer.close()

        try {
            // checks server's status code first
            val status = httpConnection.responseCode
            if (status == HttpURLConnection.HTTP_OK) {
                val reader = BufferedReader(
                    InputStreamReader(
                        httpConnection
                            .inputStream
                    )
                )
                val response = reader.use(BufferedReader::readText)
                httpConnection.disconnect()
                onFileUploadedListener?.onFileUploadingSuccess(response)
            } else {
                onFileUploadedListener?.onFileUploadingFailed(status)
            }

        } catch (e: IOException) {
            e.printStackTrace()
        }

    }

    class OnFileUploadedListener {
        private val LOGGER: Logger = Logger()
        fun onFileUploadingSuccess(response: String) {
            LOGGER.i(response)
        }

        fun onFileUploadingFailed(responseCode: Int) {
            LOGGER.i(responseCode.toString())
        }
    }

}