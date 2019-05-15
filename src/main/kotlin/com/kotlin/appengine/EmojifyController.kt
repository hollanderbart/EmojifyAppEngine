package com.kotlin.appengine

import com.google.cloud.storage.Bucket
import com.google.cloud.storage.Storage
import com.google.cloud.storage.StorageOptions
import com.google.cloud.vision.v1.*
import org.springframework.beans.factory.annotation.Value
import org.springframework.context.annotation.Bean
import org.springframework.core.io.ClassPathResource
import org.springframework.http.HttpStatus
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.RestController
import java.awt.Polygon
import java.awt.image.BufferedImage
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.nio.channels.Channels
import java.util.logging.Logger
import javax.imageio.ImageIO

enum class Emoji {
    JOY, ANGER, SURPRISE, SORROW, HAT, NONE
}

val errorMessage = mapOf(
        100 to "Other",
        101 to "Slashes are intentionally forbidden in objectName.",
        102 to "storage.bucket.name is missing in application.properties.",
        103 to "Blob specified doesn't exist in bucket.",
        104 to "blob ContentType is null.",
        105 to "Size of responsesList is not 1.",
        106 to "objectName is null.",
        107 to "We couldn't detect faces in your image."
)

// Returns best emoji based on detected emotions likelihoods
fun bestEmoji(annotation: FaceAnnotation): Emoji {
    val emotionsLikelihood = listOf(Likelihood.VERY_LIKELY, Likelihood.LIKELY, Likelihood.POSSIBLE)
    val emotions = mapOf(
            Emoji.JOY to annotation.joyLikelihood,
            Emoji.ANGER to annotation.angerLikelihood,
            Emoji.SURPRISE to annotation.surpriseLikelihood,
            Emoji.SORROW to annotation.sorrowLikelihood)
    for (likelihood in emotionsLikelihood) { // In this order: VERY_LIKELY, LIKELY, POSSIBLE
        for (emotion in emotions) { // In this order: JOY, ANGER, SURPRISE, SORROW (https://kotlinlang.org/api/latest/jvm/stdlib/kotlin.collections/map-of.html)
            if (emotion.value == likelihood) return emotion.key // Returns emotion corresponding to likelihood
        }
    }
    return Emoji.NONE
}

fun hatEmoji(annotation: FaceAnnotation): Emoji {
    val emotionsLikelihood = listOf(Likelihood.VERY_LIKELY, Likelihood.LIKELY, Likelihood.POSSIBLE)
    for (likelihood in emotionsLikelihood) {
        if (annotation.headwearLikelihood == likelihood) {
            return Emoji.HAT
        }
    }
    return Emoji.NONE
}

data class EmojifyResponse(
        val objectPath: String? = null,
        val emojifiedUrl: String? = null,
        val statusCode: HttpStatus = HttpStatus.OK,
        val errorCode: Int? = null,
        val errorMessage: String? = null
)

@RestController
class EmojifyController(@Value("\${storage.bucket.name}") val bucketName: String, val storage: Storage, val vision: ImageAnnotatorClient) {

    @GetMapping("/")
    fun hello(): String {
        return "Hi there!"
    }

    companion object {
        val log: Logger = Logger.getLogger(EmojifyController::class.java.name)
    }

    val emojiBufferedImage = mapOf(
            Emoji.JOY to retrieveEmoji("joy.png"),
            Emoji.ANGER to retrieveEmoji("anger.png"),
            Emoji.SURPRISE to retrieveEmoji("surprise.png"),
            Emoji.SORROW to retrieveEmoji("sorrow.png"),
            Emoji.HAT to retrieveEmoji("hat.png"),
            Emoji.NONE to retrieveEmoji("none.png")
    )

    private final fun retrieveEmoji(name: String): BufferedImage {
        return ImageIO.read(ClassPathResource("emojis/$name").inputStream)
    }

    fun streamFromGCS(blobName: String): BufferedImage {
        val strm: InputStream = Channels.newInputStream(storage.reader(bucketName, blobName))
        return ImageIO.read(strm)
    }

    fun errorResponse(statusCode: HttpStatus, errorCode: Int = 100, msg: String? = null): EmojifyResponse {
        val err = msg ?: errorMessage[errorCode]
        log.severe(err)
        return EmojifyResponse(statusCode = statusCode, errorCode = errorCode, errorMessage = err)
    }

    @GetMapping("/emojify")
    fun emojify(@RequestParam(value = "objectName") objectName: String): EmojifyResponse {

        if (objectName.isEmpty()) return errorResponse(HttpStatus.BAD_REQUEST, 106)

        if (objectName.contains('/')) return errorResponse(HttpStatus.BAD_REQUEST, 101)

        val bucket = storage.get(bucketName) ?: return errorResponse(HttpStatus.INTERNAL_SERVER_ERROR, 102)
        val publicUrl: String =
                "https://storage.googleapis.com/$bucketName/emojified/emojified-$objectName" // api response
        val blob = bucket.get(objectName) ?: return errorResponse(HttpStatus.BAD_REQUEST, 103)
        val imgType = blob.contentType?.substringAfter('/') ?: return errorResponse(HttpStatus.BAD_REQUEST, 104)

        // Setting up image annotation request
        val source = ImageSource.newBuilder().setGcsImageUri("gs://$bucketName/$objectName").build()
        val img = Image.newBuilder().setSource(source).build()
        val feat = Feature.newBuilder().setMaxResults(100).setType(Feature.Type.FACE_DETECTION).build()
        val request = AnnotateImageRequest.newBuilder()
                .addFeatures(feat)
                .setImage(img)
                .build()

        // Calls vision api on above image annotation requests
        val response = vision.batchAnnotateImages(listOf(request))
        if (response.responsesList.size != 1) return errorResponse(HttpStatus.INTERNAL_SERVER_ERROR, 105)
        val resp = response.responsesList[0]
        if (resp.hasError()) return errorResponse(HttpStatus.INTERNAL_SERVER_ERROR, 100, resp.error.message)

        // Writing source image to InputStream
        val imgBuff = streamFromGCS(objectName)
        val gfx = imgBuff.createGraphics()

        if (resp.faceAnnotationsList.size == 0) return errorResponse(HttpStatus.BAD_REQUEST, 107)

        for (annotation in resp.faceAnnotationsList) {
            val imgEmoji = emojiBufferedImage[bestEmoji(annotation)]
            val poly = Polygon()
            for (vertex in annotation.fdBoundingPoly.verticesList) {
                poly.addPoint(vertex.x, vertex.y)
            }
            val height = poly.ypoints[2] - poly.ypoints[0]
            val width = poly.xpoints[1] - poly.xpoints[0]
            // Draws emoji on detected face
            gfx.drawImage(imgEmoji, poly.xpoints[0], poly.ypoints[1], height, width, null)
        }

        // Writing emojified image to OutputStream
        val outputStream = ByteArrayOutputStream()
        ImageIO.write(imgBuff, imgType, outputStream)

        // Uploading emojified image to GCS and making it public
        bucket.create(
                "emojified/emojified-$objectName",
                outputStream.toByteArray(),
                Bucket.BlobTargetOption.predefinedAcl(Storage.PredefinedAcl.PUBLIC_READ)
        )

        // Everything went well!
        return EmojifyResponse(
                objectPath = "emojified/emojified-$objectName",
                emojifiedUrl = publicUrl
        )
    }
}