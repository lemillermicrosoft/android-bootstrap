package com.example.test

import android.Manifest
import android.animation.Animator
import android.animation.AnimatorListenerAdapter
import android.animation.ObjectAnimator
import android.app.Activity
import android.app.AlertDialog
import android.content.Context
import android.content.DialogInterface
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.Matrix
import android.hardware.Camera
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraManager
import android.media.ImageReader
import android.media.MediaActionSound
import android.os.*
import android.text.InputType
import android.util.DisplayMetrics
import android.util.Size
import android.view.KeyEvent
import android.view.Surface
import android.view.View
import android.view.WindowManager
import android.view.animation.*
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputMethodManager
import android.widget.*
import androidx.annotation.RequiresApi
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import com.example.test.api.LobeApiHelper
import com.example.test.api.LobeApiHelper.Companion.getProjects
import com.example.test.api.LobeApiHelper.Companion.loadProject
import com.example.test.api.LobeApiHelper.Companion.postExample
import com.example.test.api.Project
import com.example.test.env.ImageUtils
import com.example.test.env.Logger
import com.example.test.tflite.Classifier
import org.tensorflow.lite.examples.detection.LegacyCameraConnectionFragment
import java.io.*
import java.text.SimpleDateFormat
import java.util.*


@RequiresApi(Build.VERSION_CODES.KITKAT)
abstract class CameraActivity : Activity(), ImageReader.OnImageAvailableListener,
    Camera.PreviewCallback, CompoundButton.OnCheckedChangeListener, View.OnClickListener {

    private val debug = false

    private val LOGGER: Logger = Logger()
    private val PERMISSIONS_REQUEST = 1
    private var handler: Handler? = null
    private var handlerThread: HandlerThread? = null
    private val GALLARY_REQUEST_CODE = 123
    var screenHeight: Int? = null
    var screenWidth: Int? = null
    private val PERMISSION_CAMERA = Manifest.permission.CAMERA

    protected var previewWidth = 0
    protected var previewHeight = 0
    private var isProcessingFrame = false
    private val yuvBytes = arrayOfNulls<ByteArray>(3)
    private var rgbBytes: IntArray? = null
    private var yRowStride = 0
    private var postInferenceCallback: Runnable? = null
    private var imageConverter: Runnable? = null
    var rgbFrameBitmap: Bitmap? = null

    var useImage = false
    var useFront = false
    var imageView: ImageView? = null
    var label: TextView? = null
    var progressBar: ProgressBar? = null
    var outer: View? = null
    var inputData: ByteArray? = null

//    var modifyingLabel = false


    val shutterSound = MediaActionSound()
    var imageToUpload: ByteArray? = null

    private val device: Classifier.Device = Classifier.Device.CPU

    val nThreads: Int = 4
    private val model: Classifier.Model = Classifier.Model.FLOAT_MOBILENET

//    public fun setModifyingLabel(isModifying: Boolean) {
//        modifyingLabel = isModifying
//    }

//    public fun setModifying(isModifying: Boolean) {
//        modifyingLabel = isModifying
//    }

    fun getNumThreads(): Int {
        return nThreads
    }

    public fun setMode(useimg: Boolean) {
        useImage = useimg
    }

    protected open fun getModel(): Classifier.Model? {
        return model
    }

    protected open fun getDevice(): Classifier.Device? {
        return device
    }

    protected var projects = listOf<Project>()
    protected var project_names = arrayOf<String>()
    protected var skipPrediction = false

    @RequiresApi(Build.VERSION_CODES.O)
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        imageView = findViewById(R.id.myImageView)
        label = findViewById(R.id.textView)
        progressBar = findViewById(R.id.ProgressBar)
        outer = findViewById(R.id.relativeLayout)


        // TODO - Proposal
        // Image onClick - upload unlabeled
        // Text onClick - upload labeled
        // Text onLongClick -- modify label + upload (stretch goal)

        label!!.setCursorVisible(false);

        // Text onClick - upload labeled
        label!!.setOnClickListener {
            println("clicked")
            postLabeledImage(imageToUpload!!, label!!.text.toString())
        }

        // Text onLongClick -- modify label + upload (stretch goal)
        label!!.setOnLongClickListener {
            println("long clicked")
            label!!.setCursorVisible(true);
            label!!.setFocusableInTouchMode(true);
            label!!.setInputType(InputType.TYPE_CLASS_TEXT);
            label!!.requestFocus();
            val imm: InputMethodManager =
                getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager
            imm.showSoftInput(label, InputMethodManager.SHOW_IMPLICIT)
            (label!! as EditTextBackEvent).modifyingLabel = true
            true
        }

        label!!.setOnEditorActionListener(
            object : TextView.OnEditorActionListener {
                override fun onEditorAction(
                    v: TextView?,
                    actionId: Int,
                    event: KeyEvent?
                ): Boolean {
                    if (actionId == EditorInfo.IME_ACTION_SEARCH || actionId == EditorInfo.IME_ACTION_DONE || event != null && event.action == KeyEvent.ACTION_DOWN && event.keyCode == KeyEvent.KEYCODE_ENTER
                    ) {
                        if (event == null || !event.isShiftPressed) {
                            // the user is done typing.
                            label!!.focusable = 0
                            (label!! as EditTextBackEvent).modifyingLabel = false
                            println("new label:")
                            println(label!!.text)
                            postLabeledImage(imageToUpload!!, label!!.text.toString())
                            return false // consume.
                        }
                    }
                    (label!! as EditTextBackEvent).modifyingLabel = false
                    return false // pass on to other listeners.
                }
            }
        )

        val displayMetrics = DisplayMetrics()
        windowManager.defaultDisplay.getMetrics(displayMetrics)
        screenHeight = displayMetrics.heightPixels
        screenWidth = displayMetrics.widthPixels

        if (hasPermission()) {
            setFragment(false)
        } else {
            requestPermission()
        }

        // Fetching the projects available
        getProjects(this) { projects ->
            this.projects = projects
            for (project in projects) {
                this.project_names += project.name
            }

            LOGGER.w("Projects: " + this.projects.toString())
            // Use the most recent project (first)
            LobeApiHelper.projectId = this.projects[0].id
            loadLatestProject()
        }


        shutterSound.load(MediaActionSound.SHUTTER_CLICK)
        outer!!.setOnTouchListener(object : OnSwipeTouchListener(this) {
            override fun onSwipeTop() {
                val intent = Intent()
                intent.type = "image/*"
                intent.action = Intent.ACTION_GET_CONTENT
                startActivityForResult(
                    Intent.createChooser(intent, "Select Picture"),
                    GALLARY_REQUEST_CODE
                )
                overridePendingTransition(R.anim.slide_animation, R.anim.slide_animation);
            }

            override fun onSwipeBottom() {
                changeCam()
            }

            @RequiresApi(Build.VERSION_CODES.O)
            override fun doubleTap() {
                postUnlabeledImage(imageToUpload!!)
            }

            override fun tripleTap() {
                takeScreenshot();
                //processImage(true)
                // TODO Upload to lobe!
            }
        })
    }


    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        if (requestCode == GALLARY_REQUEST_CODE && resultCode == RESULT_OK && data != null) {
            useImage = true
            val imageData = data.data
            var iStream: InputStream? = null
            try {
                iStream = contentResolver.openInputStream(imageData!!)
            } catch (e: FileNotFoundException) {
                e.printStackTrace()
            }
            try {
                inputData = getBytes(iStream!!)
            } catch (e: IOException) {
                e.printStackTrace()
            }
            imageView!!.setImageURI(imageData)
            imageView!!.animate().scaleX(1.toFloat()).alpha(1.toFloat())
                .scaleY(1.toFloat()).alpha(1.toFloat()).x(0F).y(0F).setDuration(500).start()
            imageView!!.setOnTouchListener(OnDragTouchListener(imageView, this));
        }
    }

    @Throws(IOException::class)
    open fun getBytes(inputStream: InputStream): ByteArray? {
        val byteBuffer = ByteArrayOutputStream()
        val bufferSize = 1024
        val buffer = ByteArray(bufferSize)
        var len = 0
        while (inputStream.read(buffer).also { len = it } != -1) {
            byteBuffer.write(buffer, 0, len)
        }
        return byteBuffer.toByteArray()
    }

    override fun onImageAvailable(reader: ImageReader?) {
        System.out.println("not implemented (should not be needed)")
    }

    open fun onClickBtn(v: View?) {

//        val inflater: LayoutInflater = layoutInflater
//        val dialoglayout: View = inflater.inflate(R.layout.dialog_layout, null)
//        val builder = AlertDialog.Builder(this)
//        builder.setView(dialoglayout)
//        builder.show()

        val builder = AlertDialog.Builder(this)
        builder.setTitle("Settings")

        val layout = LinearLayout(this)
        layout.orientation = LinearLayout.VERTICAL
        val ip_msg = TextView(this)
        ip_msg.setText("Set IP Address")
        layout.addView(ip_msg)

        val input = EditText(this)
        input.hint = LobeApiHelper.urlHost
        layout.addView(input)

        builder.setView(layout)

        builder.setSingleChoiceItems(this.project_names, this.projectIndex) { dialog, which ->
            this.projectIndex = which
            LobeApiHelper.projectId = this.projects[which].id
            loadLatestProject()
//            Toast.makeText(applicationContext, this.project_names[which], Toast.LENGTH_LONG).show()
//            dialog.dismiss()
        }

        builder.setPositiveButton("OK", object : DialogInterface.OnClickListener {
            override fun onClick(dialog: DialogInterface?, which: Int) {
                if (input.text.toString().length > 0) {
                    LobeApiHelper.urlHost = input.text.toString()
                }
            }
        })
        builder.setNegativeButton("Cancel", object : DialogInterface.OnClickListener {
            override fun onClick(dialog: DialogInterface, which: Int) {
                dialog.cancel()
            }
        })
        builder.show()
    }

    @RequiresApi(Build.VERSION_CODES.LOLLIPOP)
    fun changeCam() {
        var ctn: View = findViewById(R.id.container)
        val oa1: ObjectAnimator = ObjectAnimator.ofFloat(ctn, "scaleX", 1f, 0f)
        val oa2: ObjectAnimator = ObjectAnimator.ofFloat(ctn, "scaleX", 0f, 1f)
        oa1.setInterpolator(DecelerateInterpolator())
        oa2.setInterpolator(AccelerateDecelerateInterpolator())
        oa1.duration = 200
        oa2.duration = 200
        oa1.addListener(object : AnimatorListenerAdapter() {
            @RequiresApi(Build.VERSION_CODES.LOLLIPOP)
            override fun onAnimationEnd(animation: Animator?) {
                super.onAnimationEnd(animation)
                useFront = !useFront
                setFragment(useFront)
                oa2.start()
            }
        })
        oa1.start()
    }

    private fun loadLatestProject()
    {
        label!!.text = "Loading..."
        skipPrediction = true // Lee did this silly hack
        loadProject(this, LobeApiHelper.projectId) { success ->

            if (success) {
                skipPrediction = false
            } else {
                label!!.text = "Failed - Retry"
            }
        }
    }

    private fun takeScreenshot() {
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
            var w = screenWidth!!.toFloat() / screenHeight!!.toFloat() * rotatedBitmap.height
            var resizedbitmap1 =
                Bitmap.createBitmap(rotatedBitmap, 0, 0, w.toInt(), rotatedBitmap.height)
            imageView!!.setImageBitmap(resizedbitmap1)
        }

        var iv: ImageView = findViewById(R.id.allWhite)
        iv.visibility = View.VISIBLE
        val animation: Animation =
            AlphaAnimation(
                0.toFloat(),
                1.toFloat()
            ) //to change visibility from visible to invisible
        animation.duration = 100 //1 second duration for each animation cycle
        animation.interpolator = LinearInterpolator()
        animation.repeatCount = 1 //repeating indefinitely
        animation.repeatMode = Animation.REVERSE //animation will start from end point once ended.
        iv!!.startAnimation(animation)
        iv.visibility = View.INVISIBLE

        val pattern = "yyyy-MM-dd-hh-mm-ss"
        val simpleDateFormat = SimpleDateFormat(pattern)
        val date: String = simpleDateFormat.format(Date())
        try {
            val storageLoc =
                Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_PICTURES) //context.getExternalFilesDir(null);

            // create bitmap screen capture
            val v1 = outer!!
            v1.isDrawingCacheEnabled = true
            val bitmap = Bitmap.createBitmap(v1.drawingCache)
            v1.isDrawingCacheEnabled = false

            if (ContextCompat.checkSelfPermission(
                    this,
                    Manifest.permission.WRITE_EXTERNAL_STORAGE
                ) == PackageManager.PERMISSION_GRANTED
            ) {
                // Do the file write
                val imageFile = File(storageLoc, date + ".jpg")
                val outputStream = FileOutputStream(imageFile)
                val quality = 100
                bitmap.compress(Bitmap.CompressFormat.JPEG, quality, outputStream)
                outputStream.flush()
                outputStream.close()
            } else {
                // Request permission from the user
                ActivityCompat.requestPermissions(
                    this,
                    arrayOf(Manifest.permission.WRITE_EXTERNAL_STORAGE),
                    0
                )
            }
        } catch (e: Throwable) {
            // Several error may come out with file handling or DOM
            e.printStackTrace()
        }

        if (!useImage) {
            imageView!!.setImageBitmap(null)
        }
    }

    @RequiresApi(Build.VERSION_CODES.LOLLIPOP)
    override fun onPreviewFrame(bytes: ByteArray?, camera: Camera?) {
        if (isProcessingFrame) {
            System.out.println("Dropping frame!");
            return
        }
        try {
            // Initialize the storage bitmaps once when the resolution is known.
            if (rgbBytes == null) {
                val previewSize =
                    camera!!.parameters.previewSize
                previewHeight = previewSize.height
                previewWidth = previewSize.width
                rgbBytes = IntArray(previewWidth * previewHeight)
                onPreviewSizeChosen(Size(previewSize.width, previewSize.height), 90)
            }
        } catch (e: Exception) {
            throw java.lang.Exception(e)
            return
        }

        isProcessingFrame = true
        yuvBytes[0] = bytes
        yRowStride = previewWidth

        imageConverter = Runnable {
            ImageUtils.convertYUV420SPToARGB8888(bytes, previewWidth, previewHeight, rgbBytes)
        }

        postInferenceCallback = Runnable {
            camera!!.addCallbackBuffer(bytes)
            isProcessingFrame = false
        }
        processImage()
    }

    override fun onCheckedChanged(p0: CompoundButton?, p1: Boolean) {
    }

    @RequiresApi(Build.VERSION_CODES.O)
    override fun onClick(p0: View?) {
        println("view???")
        println(p0)
        postUnlabeledImage(imageToUpload!!)
    }

    // TODO This should come from a settings object
    protected var projectId = ""
    protected var projectIndex = 0

    @RequiresApi(Build.VERSION_CODES.O)
    private fun postUnlabeledImage(imageBytes: ByteArray) {
        shutterSound.play(MediaActionSound.SHUTTER_CLICK)
        postExample(this, { r: Runnable -> runInBackground(r) }, imageBytes, null)
    }

    @RequiresApi(Build.VERSION_CODES.O)
    private fun postLabeledImage(imageBytes: ByteArray, label: String) {
        shutterSound.play(MediaActionSound.SHUTTER_CLICK)
        postExample(this, { r: Runnable -> runInBackground(r) }, imageBytes, label)
    }

    private fun hasPermission(): Boolean {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            checkSelfPermission(PERMISSION_CAMERA) == PackageManager.PERMISSION_GRANTED
        } else {
            true
        }
    }

    private fun requestPermission() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            if (shouldShowRequestPermissionRationale(PERMISSION_CAMERA)) {
                Toast.makeText(
                    this@CameraActivity,
                    "Camera permission is required for this demo",
                    Toast.LENGTH_LONG
                )
                    .show()
            }
            requestPermissions(
                arrayOf(PERMISSION_CAMERA),
                PERMISSIONS_REQUEST
            )
        }
    }

    private var cameraId: String? = null
    private var fragment: LegacyCameraConnectionFragment? = null

    @RequiresApi(Build.VERSION_CODES.LOLLIPOP)
    protected open fun setFragment(useFront: Boolean) {
        this.cameraId = chooseCamera()
        this.fragment = LegacyCameraConnectionFragment(
            this,
            getLayoutId(),
            getDesiredPreviewFrameSize(),
            useFront,
            screenHeight!!,
            screenWidth!!
        )
        fragmentManager.beginTransaction().replace(R.id.container, this.fragment).commit()
    }

    @RequiresApi(Build.VERSION_CODES.LOLLIPOP)
    protected open fun getIsFrontFacing(): Boolean {
        val id = this.fragment!!.getCameraId()
        val ci = Camera.CameraInfo()
        for (i in 0 until Camera.getNumberOfCameras()) {
            Camera.getCameraInfo(i, ci)
            if (i == id) {
                return ci.facing == Camera.CameraInfo.CAMERA_FACING_FRONT
            }
        }
        return false
    }

    @RequiresApi(Build.VERSION_CODES.LOLLIPOP)
    private fun chooseCamera(): String? {
        val manager =
            getSystemService(Context.CAMERA_SERVICE) as CameraManager
        try {
            for (cameraId in manager.cameraIdList) {
                val characteristics =
                    manager.getCameraCharacteristics(cameraId)

                // We don't use a front facing camera in this sample.
                val facing = characteristics.get(CameraCharacteristics.LENS_FACING)
                if (facing != null && facing == CameraCharacteristics.LENS_FACING_FRONT) {
                    continue
                }
                val map =
                    characteristics.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP)
                        ?: continue
                return cameraId
            }
        } catch (e: java.lang.Exception) {
            LOGGER.e(e, "Not allowed to access camera")
        }
        return null
    }

    protected open fun getScreenOrientation(): Int {
        return when (windowManager.defaultDisplay.rotation) {
            Surface.ROTATION_270 -> 270
            Surface.ROTATION_180 -> 180
            Surface.ROTATION_90 -> 90
            else -> 0
        }
    }

    open fun isDebug(): Boolean {
        return debug
    }

    protected open fun readyForNextImage() {
        if (postInferenceCallback != null) {
            postInferenceCallback!!.run()
        }
    }

    protected open fun getRgbBytes(): IntArray? {
        imageConverter!!.run()
        return rgbBytes
    }

    @Synchronized
    fun runInBackground(r: Runnable) {
        handler?.post(r)
    }

    @Synchronized
    override fun onStart() {
        LOGGER.d("onStart $this")
        super.onStart()
    }

    @Synchronized
    override fun onResume() {
        LOGGER.d("onResume $this")
        super.onResume()
        handlerThread = HandlerThread("inference")
        handlerThread!!.start()
        handler = Handler(handlerThread!!.getLooper())
    }

    @Synchronized
    override fun onPause() {
        LOGGER.d("onPause $this")
        handlerThread!!.quitSafely()
        try {
            handlerThread!!.join()
            handlerThread = null
            handler = null
        } catch (e: InterruptedException) {
            LOGGER.e(e, "Exception!")
        }
        super.onPause()
    }

    @Synchronized
    override fun onStop() {
        LOGGER.d("onStop $this")
        super.onStop()
    }

    @Synchronized
    override fun onDestroy() {
        LOGGER.d("onDestroy $this")
        super.onDestroy()
    }

    protected abstract fun onPreviewSizeChosen(size: Size?, rotation: Int)
    protected abstract fun processImage()
    protected abstract fun getLayoutId(): Int
    protected abstract fun getDesiredPreviewFrameSize(): Size
}