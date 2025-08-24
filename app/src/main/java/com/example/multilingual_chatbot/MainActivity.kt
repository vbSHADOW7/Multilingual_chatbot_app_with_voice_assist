package com.example.multilingualchatbot

import android.content.Intent
import android.content.pm.PackageManager
import android.os.Bundle
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.speech.tts.TextToSpeech
import android.util.Log
import android.view.View
import android.widget.AdapterView
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.EditText
import android.widget.Spinner
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import org.vosk.Model
import org.vosk.Recognizer
import org.vosk.android.RecognitionListener
import org.vosk.android.SpeechService
import java.io.File
import java.io.FileOutputStream
import java.util.Locale

class MainActivity : AppCompatActivity() {

    private lateinit var recyclerView: RecyclerView
    private lateinit var messageInput: EditText
    private lateinit var sendButton: Button
    private lateinit var voiceButton: Button
    private lateinit var languageSpinner: Spinner
    private val messages = mutableListOf<Message>()
    private lateinit var adapter: ChatAdapter
    private lateinit var textToSpeech: TextToSpeech
    private var speechService: SpeechService? = null
    private var isListening = false
    private var useVosk = true // Toggle between Vosk and SpeechRecognizer
    private lateinit var speechRecognizer: SpeechRecognizer
    private val languages = listOf(
        "English" to "en",
        "Hindi" to "hi",
        "Telugu" to "te"
    )
    private val sharedPreferences by lazy { getSharedPreferences("ChatbotPrefs", MODE_PRIVATE) }
    private val backendUrl = "https://0782-49-37-177-117.ngrok-free.app/chat" // Update with your ngrok URL
    private val TAG = "MainActivity"
    private val SPEECH_REQUEST_CODE = 100

    override fun onCreate(savedInstanceState: Bundle?) {
        Log.d(TAG, "onCreate: Starting")
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
        Log.d(TAG, "onCreate: setContentView completed")

        // Initialize UI
        recyclerView = findViewById(R.id.chatRecyclerView)
        messageInput = findViewById(R.id.messageInput)
        sendButton = findViewById(R.id.sendButton)
        voiceButton = findViewById(R.id.voiceButton)
        languageSpinner = findViewById(R.id.languageSpinner)
        Log.d(TAG, "onCreate: UI components initialized")

        adapter = ChatAdapter(messages)
        recyclerView.layoutManager = LinearLayoutManager(this)
        recyclerView.adapter = adapter
        Log.d(TAG, "onCreate: RecyclerView setup completed")

        // Setup language spinner
        val spinnerAdapter = ArrayAdapter(this, android.R.layout.simple_spinner_item, languages.map { it.first })
        spinnerAdapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item)
        languageSpinner.adapter = spinnerAdapter
        languageSpinner.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(parent: AdapterView<*>, view: View?, position: Int, id: Long) {
                val language = languages[position].second
                sharedPreferences.edit().putString("language", language).apply()
                Log.d(TAG, "Language selected: $language")
                if (useVosk) {
                    initVosk()
                }
                updateTTSLanguage(language)
            }
            override fun onNothingSelected(parent: AdapterView<*>) {}
        }
        Log.d(TAG, "onCreate: Language spinner setup completed")

        // Initialize SpeechRecognizer
        speechRecognizer = SpeechRecognizer.createSpeechRecognizer(this)

        // Request permissions
        if (ContextCompat.checkSelfPermission(this, android.Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            ActivityCompat.requestPermissions(this, arrayOf(android.Manifest.permission.RECORD_AUDIO), 1)
            Log.d(TAG, "onCreate: Requested RECORD_AUDIO permission")
        } else {
            Log.d(TAG, "onCreate: RECORD_AUDIO permission already granted")
        }

        // Initialize TextToSpeech
        textToSpeech = TextToSpeech(this) { status ->
            if (status == TextToSpeech.SUCCESS) {
                Log.d(TAG, "TextToSpeech initialized successfully")
                updateTTSLanguage(getSelectedLanguage())
            } else {
                Log.e(TAG, "TextToSpeech initialization failed: $status")
            }
        }

        // Copy models from assets to internal storage
        copyModelsToInternalStorage()

        // Initialize Vosk
        if (useVosk) {
            initVosk()
            Log.d(TAG, "onCreate: Vosk initialization completed")
        } else {
            Log.d(TAG, "onCreate: Using SpeechRecognizer instead of Vosk")
        }

        // Button listeners
        sendButton.setOnClickListener {
            val userText = messageInput.text.toString()
            if (userText.isNotEmpty()) {
                messages.add(Message(userText, true))
                adapter.notifyItemInserted(messages.size - 1)
                messageInput.text.clear()
                recyclerView.scrollToPosition(messages.size - 1)
                Log.d(TAG, "Send button clicked: $userText")
                getBackendResponse(userText)
            } else {
                Log.d(TAG, "Send button clicked but message is empty")
            }
        }

        voiceButton.setOnClickListener {
            if (ContextCompat.checkSelfPermission(this, android.Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
                messages.add(Message("Microphone permission denied. Please grant permission to use voice input.", false))
                adapter.notifyItemInserted(messages.size - 1)
                ActivityCompat.requestPermissions(this, arrayOf(android.Manifest.permission.RECORD_AUDIO), 1)
                Log.d(TAG, "Voice button: Permission denied, requesting again")
            } else if (!isListening) {
                if (useVosk) {
                    val listener = object : RecognitionListener {
                        override fun onPartialResult(hypothesis: String?) {
                            Log.d(TAG, "Voice: Partial result received: $hypothesis")
                        }

                        override fun onResult(hypothesis: String?) {
                            Log.d(TAG, "Voice: onResult called with hypothesis: $hypothesis")
                            val voiceText = JSONObject(hypothesis ?: "{}").optString("text", "")
                            if (voiceText.isNotEmpty()) {
                                messages.add(Message(voiceText, true))
                                adapter.notifyItemInserted(messages.size - 1)
                                recyclerView.scrollToPosition(messages.size - 1)
                                getBackendResponse(voiceText)
                                Log.d(TAG, "Voice: Result processed: $voiceText")
                            } else {
                                Log.d(TAG, "Voice: Result empty or invalid JSON")
                            }
                            voiceButton.text = "Voice"
                            isListening = false
                        }

                        override fun onFinalResult(hypothesis: String?) {
                            Log.d(TAG, "Voice: onFinalResult called with hypothesis: $hypothesis")
                            val voiceText = JSONObject(hypothesis ?: "{}").optString("text", "")
                            if (voiceText.isNotEmpty()) {
                                messages.add(Message(voiceText, true))
                                adapter.notifyItemInserted(messages.size - 1)
                                recyclerView.scrollToPosition(messages.size - 1)
                                getBackendResponse(voiceText)
                                Log.d(TAG, "Voice: Final result processed: $voiceText")
                            } else {
                                Log.d(TAG, "Voice: Final result empty or invalid JSON")
                            }
                            voiceButton.text = "Voice"
                            isListening = false
                        }

                        override fun onError(exception: Exception?) {
                            messages.add(Message("Speech recognition error: ${exception?.message}", false))
                            adapter.notifyItemInserted(messages.size - 1)
                            voiceButton.text = "Voice"
                            isListening = false
                            Log.e(TAG, "Voice: Error: ${exception?.message}")
                        }

                        override fun onTimeout() {
                            messages.add(Message("Speech recognition timeout", false))
                            adapter.notifyItemInserted(messages.size - 1)
                            voiceButton.text = "Voice"
                            isListening = false
                            Log.d(TAG, "Voice: Timeout occurred")
                        }
                    }
                    try {
                        speechService?.startListening(listener)
                        voiceButton.text = "Stop"
                        isListening = true
                        Log.d(TAG, "Voice button: Started listening with Vosk")
                    } catch (e: Exception) {
                        messages.add(Message("Error starting speech recognition: ${e.message}", false))
                        adapter.notifyItemInserted(messages.size - 1)
                        Log.e(TAG, "Voice button: Error starting listening: ${e.message}")
                    }
                } else {
                    // Use SpeechRecognizer as a fallback
                    val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH)
                    intent.putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
                    intent.putExtra(RecognizerIntent.EXTRA_LANGUAGE, when (getSelectedLanguage()) {
                        "hi" -> "hi-IN"
                        "te" -> "te-IN"
                        else -> "en-US"
                    })
                    intent.putExtra(RecognizerIntent.EXTRA_PROMPT, "Speak now...")
                    try {
                        startActivityForResult(intent, SPEECH_REQUEST_CODE)
                        voiceButton.text = "Stop"
                        isListening = true
                        Log.d(TAG, "Voice button: Started listening with SpeechRecognizer")
                    } catch (e: Exception) {
                        messages.add(Message("Error starting SpeechRecognizer: ${e.message}", false))
                        adapter.notifyItemInserted(messages.size - 1)
                        Log.e(TAG, "Voice button: Error starting SpeechRecognizer: ${e.message}")
                    }
                }
            } else {
                if (useVosk) {
                    speechService?.stop()
                }
                voiceButton.text = "Voice"
                isListening = false
                Log.d(TAG, "Voice button: Stopped listening")
            }
        }
        Log.d(TAG, "onCreate: Button listeners setup completed")
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == SPEECH_REQUEST_CODE && resultCode == RESULT_OK && data != null) {
            val results = data.getStringArrayListExtra(RecognizerIntent.EXTRA_RESULTS)
            val voiceText = results?.get(0) ?: ""
            if (voiceText.isNotEmpty()) {
                messages.add(Message(voiceText, true))
                adapter.notifyItemInserted(messages.size - 1)
                recyclerView.scrollToPosition(messages.size - 1)
                getBackendResponse(voiceText)
                Log.d(TAG, "SpeechRecognizer: Result processed: $voiceText")
            } else {
                Log.d(TAG, "SpeechRecognizer: Result empty")
            }
        }
        voiceButton.text = "Voice"
        isListening = false
    }

    private fun copyModelsToInternalStorage() {
        Log.d(TAG, "copyModelsToInternalStorage: Starting")
        val modelFolders = listOf(
            "vosk-model-small-en-us-0.15",
            "vosk-model-small-hi-0.22",
            "vosk-model-small-te-0.42"
        )
        for (modelFolder in modelFolders) {
            val destDir = File(filesDir, modelFolder)
            Log.d(TAG, "copyModelsToInternalStorage: Checking destination directory: ${destDir.absolutePath}")

            // Always delete existing directory to force a fresh copy
            if (destDir.exists()) {
                try {
                    destDir.deleteRecursively()
                    Log.d(TAG, "copyModelsToInternalStorage: Deleted existing directory: ${destDir.absolutePath}")
                } catch (e: Exception) {
                    Log.e(TAG, "copyModelsToInternalStorage: Error deleting directory ${destDir.absolutePath}: ${e.message}")
                    messages.add(Message("Error deleting old model directory $modelFolder: ${e.message}", false))
                    adapter.notifyItemInserted(messages.size - 1)
                }
            }

            try {
                destDir.mkdirs()
                Log.d(TAG, "copyModelsToInternalStorage: Created directory: ${destDir.absolutePath}")
                val files = assets.list(modelFolder)
                if (files.isNullOrEmpty()) {
                    Log.e(TAG, "copyModelsToInternalStorage: No files found in assets for $modelFolder")
                    messages.add(Message("Error: No files found in assets for $modelFolder", false))
                    adapter.notifyItemInserted(messages.size - 1)
                    continue
                }
                Log.d(TAG, "copyModelsToInternalStorage: Found ${files.size} files in assets for $modelFolder: ${files.joinToString()}")

                // Recursively copy all files and subdirectories
                copyAssetFolder(modelFolder, destDir)

                // Verify the files were copied
                val copiedFiles = destDir.list()
                if (copiedFiles.isNullOrEmpty()) {
                    Log.e(TAG, "copyModelsToInternalStorage: No files found in ${destDir.absolutePath} after copying")
                    messages.add(Message("Error: No files found in ${destDir.absolutePath} after copying", false))
                    adapter.notifyItemInserted(messages.size - 1)
                } else {
                    Log.d(TAG, "copyModelsToInternalStorage: Copied files to ${destDir.absolutePath}: ${copiedFiles.joinToString()}")
                }
            } catch (e: Exception) {
                Log.e(TAG, "copyModelsToInternalStorage: Error copying $modelFolder: ${e.message}")
                messages.add(Message("Error copying model $modelFolder: ${e.message}", false))
                adapter.notifyItemInserted(messages.size - 1)
            }
        }
        Log.d(TAG, "copyModelsToInternalStorage: Completed")
    }

    private fun copyAssetFolder(assetPath: String, destDir: File) {
        val files = assets.list(assetPath)
        if (files.isNullOrEmpty()) {
            Log.d(TAG, "copyAssetFolder: No files found in $assetPath")
            return
        }
        for (fileName in files) {
            val assetFilePath = if (assetPath.isEmpty()) fileName else "$assetPath/$fileName"
            val destFile = File(destDir, fileName)
            val subFiles = assets.list(assetFilePath)
            if (subFiles.isNullOrEmpty()) {
                // It's a file, copy it
                try {
                    assets.open(assetFilePath).use { input ->
                        FileOutputStream(destFile).use { output ->
                            val bytesCopied = input.copyTo(output)
                            Log.d(TAG, "copyAssetFolder: Copied $assetFilePath to ${destFile.absolutePath}, $bytesCopied bytes")
                        }
                    }
                } catch (e: Exception) {
                    Log.e(TAG, "copyAssetFolder: Error copying file $assetFilePath: ${e.message}")
                    messages.add(Message("Error copying file $assetFilePath: ${e.message}", false))
                    adapter.notifyItemInserted(messages.size - 1)
                }
            } else {
                // It's a directory, recurse
                destFile.mkdirs()
                copyAssetFolder(assetFilePath, destFile)
            }
        }
    }

    private fun initVosk() {
        Log.d(TAG, "initVosk: Starting")
        val language = getSelectedLanguage()
        val modelFolder = when (language) {
            "hi" -> "vosk-model-small-hi-0.22"
            "te" -> "vosk-model-small-te-0.42"
            else -> "vosk-model-small-en-us-0.15"
        }
        try {
            val modelDir = File(filesDir, modelFolder)
            val modelPath = modelDir.absolutePath
            Log.d(TAG, "initVosk: Attempting to load model for $language from $modelPath")
            if (!modelDir.exists()) {
                Log.e(TAG, "initVosk: Model directory does not exist: $modelPath")
                messages.add(Message("Error: Model directory for $language does not exist", false))
                adapter.notifyItemInserted(messages.size - 1)
                return
            }
            val modelFiles = modelDir.list()
            if (modelFiles.isNullOrEmpty()) {
                Log.e(TAG, "initVosk: Model directory $modelPath is empty")
                messages.add(Message("Error: Model directory for $language is empty", false))
                adapter.notifyItemInserted(messages.size - 1)
                return
            }
            Log.d(TAG, "initVosk: Model directory $modelPath contains: ${modelFiles.joinToString()}")
            val model = Model(modelPath)
            speechService?.shutdown()
            speechService = SpeechService(Recognizer(model, 16000.0f), 16000.0f)
            Log.d(TAG, "initVosk: Successfully loaded model for $language at $modelPath")
        } catch (e: Exception) {
            messages.add(Message("Error loading Vosk model for $language: ${e.message}", false))
            adapter.notifyItemInserted(messages.size - 1)
            Log.e(TAG, "initVosk: Error loading model for $language: ${e.message}")
        }
        Log.d(TAG, "initVosk: Completed")
    }

    private fun updateTTSLanguage(language: String) {
        Log.d(TAG, "updateTTSLanguage: Setting language to $language")
        val locale = when (language) {
            "hi" -> Locale("hi", "IN")
            "te" -> Locale("te", "IN")
            else -> Locale.US
        }
        if (textToSpeech.isLanguageAvailable(locale) == TextToSpeech.LANG_COUNTRY_AVAILABLE) {
            textToSpeech.language = locale
            Log.d(TAG, "updateTTSLanguage: TTS set to $locale")
        } else {
            messages.add(Message("TTS not available for $language", false))
            adapter.notifyItemInserted(messages.size - 1)
            textToSpeech.language = Locale.US
            Log.w(TAG, "updateTTSLanguage: TTS not available for $language, defaulting to US English")
        }
    }

    private fun getSelectedLanguage(): String {
        val language = sharedPreferences.getString("language", "en") ?: "en"
        Log.d(TAG, "getSelectedLanguage: Selected language is $language")
        return language
    }

    private fun getBackendResponse(userText: String) {
        Log.d(TAG, "getBackendResponse: Sending request for text: $userText")
        CoroutineScope(Dispatchers.IO).launch {
            try {
                val client = OkHttpClient()
                val json = JSONObject().apply {
                    put("text", userText)
                    put("language", getSelectedLanguage())
                }
                val request = Request.Builder()
                    .url(backendUrl)
                    .post(json.toString().toRequestBody("application/json".toMediaType()))
                    .build()
                Log.d(TAG, "getBackendResponse: Sending request to $backendUrl")
                val response = client.newCall(request).execute()
                val responseBody = response.body?.string()
                val botText = JSONObject(responseBody).getString("response")

                withContext(Dispatchers.Main) {
                    messages.add(Message(botText, false))
                    adapter.notifyItemInserted(messages.size - 1)
                    recyclerView.scrollToPosition(messages.size - 1)
                    textToSpeech.speak(botText, TextToSpeech.QUEUE_FLUSH, null, null)
                    Log.d(TAG, "getBackendResponse: Received response: $botText")
                }
            } catch (e: Exception) {
                withContext(Dispatchers.Main) {
                    messages.add(Message("Error: ${e.message}", false))
                    adapter.notifyItemInserted(messages.size - 1)
                    Log.e(TAG, "getBackendResponse: Error: ${e.message}")
                }
            }
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        if (useVosk) {
            speechService?.stop()
            speechService?.shutdown()
        }
        speechRecognizer.destroy()
        textToSpeech.shutdown()
        Log.d(TAG, "onDestroy: Cleaned up resources")
    }
}