// The parts of sherpa-onnx's Kotlin API (com.k2fsa.sherpa.onnx, in app/libs/sherpa-onnx.aar)
// that SherpaRuntime uses, with the same parameter names, so named arguments type-check.
package com.k2fsa.sherpa.onnx

class FeatureConfig(var sampleRate: Int = 16000, var featureDim: Int = 80, var dither: Float = 0f)

class OfflineTransducerModelConfig(var encoder: String = "", var decoder: String = "", var joiner: String = "")

class OfflineModelConfig(
    var transducer: OfflineTransducerModelConfig = OfflineTransducerModelConfig(),
    var numThreads: Int = 1,
    var debug: Boolean = false,
    var provider: String = "cpu",
    var modelType: String = "",
    var tokens: String = "",
)

class OfflineRecognizerConfig(
    var featConfig: FeatureConfig = FeatureConfig(),
    var modelConfig: OfflineModelConfig = OfflineModelConfig(),
    var decodingMethod: String = "greedy_search",
)

class OfflineRecognizerResult(val text: String)

class OfflineStream {
    fun acceptWaveform(samples: FloatArray, sampleRate: Int): Unit = TODO()
    fun release(): Unit = TODO()
}

class OfflineRecognizer(assetManager: android.content.res.AssetManager? = null, config: OfflineRecognizerConfig) {
    fun createStream(): OfflineStream = TODO()
    fun decode(stream: OfflineStream): Unit = TODO()
    fun getResult(stream: OfflineStream): OfflineRecognizerResult = TODO()
    fun release(): Unit = TODO()
}
